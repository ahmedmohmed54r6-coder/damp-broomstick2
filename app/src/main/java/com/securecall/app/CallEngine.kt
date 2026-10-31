package com.securecall.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.webrtc.*

/** Thin wrapper around WebRTC: one 1-to-1 call, no signaling server (codes are exchanged by the users). */
class CallEngine(
    private val app: Context,
    private val egl: EglBase,
    private val localView: SurfaceViewRenderer,
    private val remoteView: SurfaceViewRenderer,
    private val onState: (String) -> Unit,
    private val onConnected: () -> Unit
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

    fun start(iceServers: List<PeerConnection.IceServer>) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f

        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("a0", audioSource)

        val cap = createCapturer()
        capturer = cap
        helper = SurfaceTextureHelper.create("capture", egl.eglBaseContext)
        videoSource = f.createVideoSource(false)
        cap?.initialize(helper, app, videoSource!!.capturerObserver)
        try { cap?.startCapture(640, 480, 24) } catch (_: Exception) {}
        videoTrack = f.createVideoTrack("v0", videoSource)
        videoTrack?.addSink(localView)

        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val p = f.createPeerConnection(cfg, observer)!!
        pc = p
        p.addTrack(audioTrack, listOf("s0"))
        p.addTrack(videoTrack, listOf("s0"))
    }

    private fun createCapturer(): VideoCapturer? {
        val en = Camera2Enumerator(app)
        val names = en.deviceNames
        for (n in names) if (en.isFrontFacing(n)) en.createCapturer(n, null)?.let { return it }
        for (n in names) en.createCapturer(n, null)?.let { return it }
        return null
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            main.post {
                if (closed) return@post
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        onState("متصل")
                        if (!connectedFired) { connectedFired = true; onConnected() }
                    }
                    PeerConnection.IceConnectionState.CHECKING -> onState("جارٍ الاتصال...")
                    PeerConnection.IceConnectionState.DISCONNECTED -> onState("انقطع الاتصال مؤقتاً")
                    PeerConnection.IceConnectionState.FAILED ->
                        onState("فشل الاتصال المباشر (قد تحتاج خادم TURN أو شبكة أخرى)")
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
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onTrack(t: RtpTransceiver?) {
            val tr = t?.receiver?.track()
            if (tr is VideoTrack) main.post {
                if (closed) return@post
                remoteTrack = tr
                tr.addSink(remoteView)
            }
        }
    }

    private fun waitGather(cb: (String) -> Unit) {
        gatherCb = cb
        if (pc?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) finishGather()
        else main.postDelayed({ finishGather() }, 7000)
    }

    private fun finishGather() {
        val cb = gatherCb ?: return
        gatherCb = null
        val d = pc?.localDescription?.description ?: return
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
        try { videoTrack?.removeSink(localView) } catch (_: Exception) {}
        try { remoteTrack?.removeSink(remoteView) } catch (_: Exception) {}
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { pc?.close(); pc?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { helper?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        pc = null; factory = null
    }
}
