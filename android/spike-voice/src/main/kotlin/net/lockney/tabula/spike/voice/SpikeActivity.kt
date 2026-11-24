package net.lockney.tabula.spike.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The spike's only screen. It exists so the app can be launched, so a person
 * can grant the microphone with a tap when Fire OS has withdrawn the adb grant
 * (P7.0 question 1), and so the device is not a headless app with no way in.
 * Everything measured is driven over adb; see tools/device/check_p70.sh.
 */
class SpikeActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Scripted entry: adb cannot start a non-exported service, so the
        // exported activity is the door. With a `cmd` extra it hands the job to
        // the service and disappears at once, before it draws anything.
        val cmd = intent?.getStringExtra("cmd")
        if (cmd != null) {
            val job = Intent(this, SpikeService::class.java).putExtras(intent).setAction(cmd)
            // The activity is not resumed yet when this runs, so the app still
            // counts as background: a foreground service is the only legal start.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(job)
            } else {
                startService(job)
            }
            finish()
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(64, 64, 64, 64)
        }
        status = TextView(this).apply { textSize = 16f }
        layout.addView(status)
        layout.addView(
            Button(this).apply {
                text = "Grant microphone"
                setOnClickListener {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                }
            },
        )
        layout.addView(
            Button(this).apply {
                text = "Play the 5 s test clip"
                setOnClickListener {
                    startService(
                        Intent(this@SpikeActivity, SpikeService::class.java).putExtra("cmd", "play"),
                    )
                }
            },
        )
        layout.addView(
            TextView(this).apply {
                textSize = 12f
                text = "Driven over adb: tools/device/check_p70.sh collects the numbers. " +
                    "This app's id is net.lockney.tabula.spike.voice; it never touches the " +
                    "paired Tabula app."
            },
        )
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        status.text = if (granted) {
            "Microphone: granted"
        } else {
            "Microphone: not granted. Tap below, or run\n" +
                "adb shell pm grant net.lockney.tabula.spike.voice android.permission.RECORD_AUDIO"
        }
    }

    private companion object {
        const val REQUEST_MIC = 1
    }
}
