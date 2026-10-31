package com.securecall.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.webrtc.*
import java.nio.ByteBuffer

/** Thin wrapper around WebRTC: one 1-to-1 call, no signaling server (codes are exchanged by the users). */
class CallEngine(
    private val app: Context,
    private val egl: EglBase,
    private val localView: SurfaceViewRenderer,
    private val remoteView: SurfaceViewRenderer,
    private val onState: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDiag: (String) -> Unit,
    private val onCommand: (String) -> Unit,
    private val onEnded: () -> Unit,
    private val onLevel: (Double) -> Unit
) {
    open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var remoteTrack: VideoTrack? = null
    private var gatherCb: ((String) -> Unit)? = null
    private var connectedFired = false
    private var closed = false
    private var controller = false
    private var dc: DataChannel? = null
    private var videoWanted = false
    private var preferFront = true
    private var lowRes = false
    private var lastIce: PeerConnection.IceConnectionState? = null

    private var enumerator: CameraEnumerator? = null
    private var camNames: List<String> = emptyList()
    private var camIndex = -1
    private var camStatus = "الكاميرا: جارٍ التشغيل..."

    fun start(iceServers: List<PeerConnection.IceServer>, withVideo: Boolean, isController: Boolean, lowResolution: Boolean = false) {
        controller = isController
        lowRes = lowResolution
        videoWanted = withVideo
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )
        // Software VP8/VP9 only: hardware encoders/decoders differ a lot between phones (a broken one
        // gives a black picture on the other side). Software codecs behave the same everywhere.
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()
        factory = f

        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("a0", audioSource)

        helper = SurfaceTextureHelper.create("capture", egl.eglBaseContext)
        videoSource = f.createVideoSource(false)
        videoTrack = f.createVideoTrack("v0", videoSource)
        videoTrack?.addSink(localView)
        videoTrack?.setEnabled(withVideo)
        if (withVideo) startCamera() else camStatus = "بدون كاميرا محلية"

        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            // With a TURN server: force the relay path. The relay address stays alive while the codes
            // travel through the chat app, and it also hides both phones' IP addresses.
            if (iceServers.any { sv -> sv.urls.any { u -> u.startsWith("turn") } })
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        val p = f.createPeerConnection(cfg, observer)!!
        pc = p
        p.addTrack(audioTrack, listOf("s0"))
        p.addTrack(videoTrack, listOf("s0"))
        // Control channel (parent -> child): switch camera, video on/off, end. It runs inside the same
        // DTLS-encrypted connection, which is only established with the paired code.
        if (controller) {
            dc = p.createDataChannel("ctl", DataChannel.Init())
            dc?.registerObserver(dcObs)
        }
    }

    private val dcObs = object : DataChannel.Observer {
        override fun onBufferedAmountChange(l: Long) {}
        override fun onStateChange() {}
        override fun onMessage(b: DataChannel.Buffer?) {
            val bb = b?.data ?: return
            val bytes = ByteArray(bb.remaining())
            bb.get(bytes)
            val msg = String(bytes)
            main.post { if (!closed) handleCmd(msg) }
        }
    }

    private fun handleCmd(c: String) {
        when (c) {
            "bye" -> { onEnded(); return }
            "video:on", "cam:front" -> if (!controller) useFacing(true)
            "cam:back" -> if (!controller) useFacing(false)
            "video:off" -> if (!controller) videoOff()
        }
        onCommand(c)
    }

    fun sendCmd(c: String) {
        val ch = dc ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(ByteBuffer.wrap(c.toByteArray()), false))
    }

    // ---------- camera (tries every camera until one works) ----------
    private fun startCamera() {
        val en: CameraEnumerator = try {
            if (Camera2Enumerator.isSupported(app)) Camera2Enumerator(app) else Camera1Enumerator(false)
        } catch (e: Exception) { Camera1Enumerator(false) }
        enumerator = en
        val names = en.deviceNames.toList()
        camNames = if (preferFront) names.filter { en.isFrontFacing(it) } + names.filter { !en.isFrontFacing(it) }
        else names.filter { !en.isFrontFacing(it) } + names.filter { en.isFrontFacing(it) }
        if (camNames.isEmpty()) { setCam("الكاميرا: لا توجد كاميرا في الجهاز"); return }
        openCamera(0)
    }

    /** Android takes the camera away when the app is in the background (e.g. while sending the code). */
    fun pauseCamera() { camIndex = -1; disposeCapturer() }
    fun resumeCamera() { if (!closed && pc != null && capturer == null && videoWanted) startCamera() }

    /** Child side: start (or restart) the camera on the requested side. */
    fun useFacing(front: Boolean) {
        preferFront = front
        videoWanted = true
        videoTrack?.setEnabled(true)
        camIndex = -1
        disposeCapturer()
        startCamera()
    }

    /** Child side: stop the camera completely (audio only). */
    fun videoOff() {
        videoWanted = false
        videoTrack?.setEnabled(false)
        camIndex = -1
        disposeCapturer()
        setCam("الكاميرا: متوقفة")
    }

    private fun setCam(s: String) { camStatus = s; onDiag(s) }

    private fun openCamera(i: Int) {
        if (closed) return
        if (i >= camNames.size) { setCam("الكاميرا: لا توجد كاميرا تعمل"); return }
        camIndex = i
        val en = enumerator ?: return
        val name = camNames[i]
        val handler = object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(e: String?) { main.post { camFailed(i, e ?: "error") } }
            override fun onCameraDisconnected() { main.post { camFailed(i, "disconnected") } }
            override fun onCameraFreezed(e: String?) { main.post { setCam("الكاميرا: متجمدة") } }
            override fun onCameraOpening(n: String?) {}
            override fun onFirstFrameAvailable() { main.post { if (i == camIndex) setCam("الكاميرا: تعمل") } }
            override fun onCameraClosed() {}
        }
        val cap = en.createCapturer(name, handler)
        if (cap == null) { camFailed(i, "createCapturer"); return }
        capturer = cap

        val fmts = try { en.getSupportedFormats(name) ?: emptyList() } catch (e: Exception) { emptyList() }
        // Low-power mode (listening / quick glance): small picture and 10 fps save battery and data.
        val target = if (lowRes) 320 * 240 else 640 * 480
        val best = fmts.minByOrNull { Math.abs(it.width * it.height - target) }
        val w = best?.width ?: (if (lowRes) 320 else 640)
        val h = best?.height ?: (if (lowRes) 240 else 480)
        val fps = ((best?.framerate?.max ?: 24000) / 1000).coerceIn(10, if (lowRes) 10 else 24)
        try {
            cap.initialize(helper, app, videoSource!!.capturerObserver)
            cap.startCapture(w, h, fps)
            setCam("الكاميرا: جارٍ الفتح (${i + 1}/${camNames.size})")
        } catch (e: Exception) {
            camFailed(i, e.message ?: "startCapture")
        }
    }

    private fun camFailed(i: Int, why: String) {
        if (closed || i != camIndex) return
        setCam("الكاميرا ${i + 1} فشلت ($why)، أجرّب التالية")
        disposeCapturer()
        openCamera(i + 1)
    }

    private fun disposeCapturer() {
        val c = capturer ?: return
        capturer = null
        try { c.stopCapture() } catch (_: Exception) {}
        try { c.dispose() } catch (_: Exception) {}
    }

    // ---------- connection ----------
    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            main.post {
                if (closed) return@post
                lastIce = s
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        onState("متصل")
                        if (!connectedFired) {
                            connectedFired = true
                            onConnected()
                            main.post(statsRunnable)
                        }
                    }
                    PeerConnection.IceConnectionState.CHECKING -> onState("جارٍ الاتصال...")
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        onState("انقطع الاتصال مؤقتاً")
                        main.postDelayed(discCheck, 15000)
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        onState("فشل الاتصال المباشر (قد تحتاج خادم TURN أو شبكة أخرى)")
                        if (connectedFired) onEnded()
                    }
                    else -> {}
                }
            }
        }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) main.post { finishGather() }
        }
        override fun onIceCandidate(c: IceCandidate?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {
            main.post { if (!closed && d != null) { dc = d; d.registerObserver(dcObs) } }
        }
        override fun onRenegotiationNeeded() {}
        override fun onTrack(t: RtpTransceiver?) {
            val tr = t?.receiver?.track()
            if (tr is VideoTrack) main.post {
                if (closed) return@post
                remoteTrack = tr
                tr.setEnabled(true)
                tr.addSink(remoteView)
            }
        }
    }

    private val discCheck = Runnable {
        if (closed || !connectedFired) return@Runnable
        if (lastIce == PeerConnection.IceConnectionState.DISCONNECTED ||
            lastIce == PeerConnection.IceConnectionState.FAILED) onEnded()
    }

    /** Every 2 s: how many video frames this phone sent and received (shows which side is not sending). */
    private val statsRunnable = object : Runnable {
        override fun run() {
            if (closed) return
            pc?.getStats { report ->
                var sent = -1L
                var recv = -1L
                var lvl = -1.0
                for (s in report.statsMap.values) {
                    val m = s.members
                    if (s.type == "outbound-rtp" && m["kind"] == "video")
                        sent = (m["framesEncoded"] as? Number)?.toLong() ?: sent
                    if (s.type == "inbound-rtp" && m["kind"] == "audio")
                        lvl = (m["audioLevel"] as? Number)?.toDouble() ?: lvl
                    if (s.type == "inbound-rtp" && m["kind"] == "video")
                        recv = (m["framesDecoded"] as? Number)?.toLong() ?: recv
                }
                main.post {
                    if (!closed && lvl >= 0) onLevel(lvl)
                    if (!closed) onDiag(
                        camStatus + "\nفيديو مُرسَل: " + (if (sent < 0) "-" else sent.toString()) +
                            " | مستقبَل: " + (if (recv < 0) "-" else recv.toString())
                    )
                }
            }
            main.postDelayed(this, 2000)
        }
    }

    private fun waitGather(cb: (String) -> Unit) {
        gatherCb = cb
        if (pc?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) finishGather()
        else main.postDelayed({ finishGather() }, 7000)
    }

    /** Summary of the ICE candidates inside the code just created (for troubleshooting). */
    var candInfo = ""
        private set

    private fun finishGather() {
        val cb = gatherCb ?: return
        gatherCb = null
        val d = pc?.localDescription?.description ?: return
        val host = Regex("typ host").findAll(d).count()
        val srflx = Regex("typ srflx").findAll(d).count()
        val relay = Regex("typ relay").findAll(d).count()
        candInfo = "المرشحات: host=$host | srflx=$srflx | relay=$relay"
        cb(d)
    }

    /** Caller: creates the offer SDP (with all ICE candidates inside). */
    fun createOffer(cb: (String) -> Unit, err: (String) -> Unit) {
        val p = pc ?: return
        p.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                p.setLocalDescription(Sdp(), d)
                main.post { waitGather(cb) }
            }
            override fun onCreateFailure(e: String?) { main.post { err(e ?: "offer failed") } }
        }, MediaConstraints())
    }

    /** Callee: applies the caller's offer and creates the answer SDP. */
    fun acceptOffer(offerSdp: String, cb: (String) -> Unit, err: (String) -> Unit) {
        val p = pc ?: return
        if (p.signalingState() != PeerConnection.SignalingState.STABLE) {
            main.post { err("تم تطبيق رمز المكالمة من قبل. اضغط إنهاء ثم ابدأ من جديد.") }
            return
        }
        p.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                p.createAnswer(object : Sdp() {
                    override fun onCreateSuccess(d: SessionDescription?) {
                        p.setLocalDescription(Sdp(), d)
                        main.post { waitGather(cb) }
                    }
                    override fun onCreateFailure(e: String?) { main.post { err(e ?: "answer failed") } }
                }, MediaConstraints())
            }
            override fun onSetFailure(e: String?) { main.post { err(e ?: "set offer failed") } }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
    }

    /** Caller: applies the callee's answer; media starts by itself afterwards. */
    fun acceptAnswer(answerSdp: String, err: (String) -> Unit) {
        val p = pc ?: return
        if (p.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            main.post { err("تم تطبيق الرد من قبل (لا تضغط اتصال مرتين). إن لم يعمل الفيديو فأنهِ المكالمة وابدأ من جديد.") }
            return
        }
        p.setRemoteDescription(object : Sdp() {
            override fun onSetFailure(e: String?) { main.post { err(e ?: "set answer failed") } }
        }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    fun setMuted(m: Boolean) { audioTrack?.setEnabled(!m) }
    fun setCameraOn(on: Boolean) { videoTrack?.setEnabled(on) }
    fun flipCamera() { (capturer as? CameraVideoCapturer)?.switchCamera(null) }

    fun close() {
        if (closed) return
        closed = true
        gatherCb = null
        main.removeCallbacksAndMessages(null)
        try { videoTrack?.removeSink(localView) } catch (_: Exception) {}
        try { remoteTrack?.removeSink(remoteView) } catch (_: Exception) {}
        disposeCapturer()
        try { dc?.unregisterObserver(); dc?.close() } catch (_: Exception) {}
        try { pc?.close(); pc?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { helper?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        pc = null; factory = null
    }
}
