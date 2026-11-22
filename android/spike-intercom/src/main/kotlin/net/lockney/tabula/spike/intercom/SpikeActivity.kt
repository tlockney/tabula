package net.lockney.tabula.spike.intercom

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast

/**
 * The spike's only screen: it forwards the intent's extras to
 * [IntercomCallService] and says what is running, so a person holding the
 * tablet can see it. The hardware checks start the service directly
 * (`am start-foreground-service`) so they can place a call with no app in
 * front (packet question 4).
 */
class SpikeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(48, 48, 48, 48)
            text = "Tabula intercom spike\n\nstarted ${SystemClock.elapsedRealtime()} ms\n"
        }
        setContentView(view)

        val intent = intent?.let { Intent(it) } ?: Intent()
        val role = intent.getStringExtra("role") ?: "call"
        intent.component = null
        intent.setClass(this, IntercomCallService::class.java)
        intent.action = IntercomCallService.ACTION_START
        if (intent.getStringExtra("room") == null) intent.putExtra("room", "spike")
        intent.putExtra("role", role)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "intercom spike: role=$role", Toast.LENGTH_SHORT).show()
        view.text = view.text.toString() + "role=$role\n\n" +
            "Logs: adb logcat -s ${IntercomCallService.TAG}\n" +
            "Stop: adb shell am startservice -a ${IntercomCallService.ACTION_STOP}\n"
    }
}
