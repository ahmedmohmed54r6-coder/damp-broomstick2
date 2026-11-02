package com.securecall.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Shown for every incoming call, whether the app was open or not. Nobody is called or recorded
 *  without the person on this phone pressing "قبول" here. */
class IncomingCallActivity : Activity() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val ui = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val callerName = intent.getStringExtra("callerName") ?: "مجهول"
        val callerId = intent.getStringExtra("callerId") ?: ""
        val type = intent.getStringExtra("type") ?: "a"

        buildUi(callerName, type)
        ring()
        ui.postDelayed(timeout, 45_000L)

        findViewById<View>(ACCEPT_ID)?.setOnClickListener { accept(callerId, callerName, type) }
        findViewById<View>(DECLINE_ID)?.setOnClickListener { finish() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object { private const val ACCEPT_ID = 9101; private const val DECLINE_ID = 9102 }

    private fun buildUi(callerName: String, type: String) {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B1220")) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        col.addView(TextView(this).apply {
            text = if (type == "v") "📹 مكالمة فيديو واردة" else "📞 مكالمة صوتية واردة"
            setTextColor(Color.parseColor("#8A97B0")); textSize = 15f; gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = callerName
            setTextColor(Color.WHITE); textSize = 30f; gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(60))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun bigBtn(id: Int, emoji: String, bg: Int) = TextView(this).apply {
            this.id = id
            text = emoji; textSize = 30f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
            isClickable = true; isFocusable = true
        }
        row.addView(bigBtn(DECLINE_ID, "✕", Color.parseColor("#EF4444")),
            LinearLayout.LayoutParams(dp(74), dp(74)).apply { marginEnd = dp(30) })
        row.addView(bigBtn(ACCEPT_ID, "✓", Color.parseColor("#22C55E")),
            LinearLayout.LayoutParams(dp(74), dp(74)))
        col.addView(row)
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun ring() {
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@IncomingCallActivity, uri)
                isLooping = true
                prepare(); start()
            }
        } catch (_: Exception) {}
        try {
            vibrator = if (Build.VERSION.SDK_INT >= 31)
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else (getSystemService(VIBRATOR_SERVICE) as Vibrator)
            val pattern = longArrayOf(0, 700, 500)
            if (Build.VERSION.SDK_INT >= 26) vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            else vibrator?.vibrate(pattern, 0)
        } catch (_: Exception) {}
    }

    private fun stopRinging() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    private fun accept(callerId: String, callerName: String, type: String) {
        val p = Store.prefs(this)
        val code = p.getString("pcode", "") ?: ""
        val myName = p.getString("pname", "") ?: "صديق"
        val myId = CallCrypto.newCode().filter { it != '-' }.take(10)
        Relay.load(p)
        if (code.isNotBlank()) {
            Rendezvous(CallCrypto.roomId(code), { }, { })
                .publish(CallCrypto.seal(code, "hello", null, "C|$myId|$myName|$callerId")) { }
        }
        val i = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_ANSWER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("peerId", callerId)
            .putExtra("peerName", callerName)
            .putExtra("type", type)
        startActivity(i)
        finish()
    }

    override fun onDestroy() {
        ui.removeCallbacks(timeout)
        stopRinging()
        super.onDestroy()
    }
}
