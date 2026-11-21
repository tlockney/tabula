package net.lockney.tabula

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The activity-side half of screen control (packet P2.3). Any activity that
 * shows the dashboard calls [attach] from onCreate and [onResume] from
 * onResume.
 *
 * - Keep-on (F-SCR-01): FLAG_KEEP_SCREEN_ON while [TabulaStatus.keepScreenOn]
 *   is set. A window flag is the platform's supported keep-awake mechanism;
 *   screen wake locks are deprecated.
 * - Window brightness (F-SCR-03 fallback): applied when the app cannot write
 *   the system backlight.
 * - Waking (F-SCR-14): [ScreenManager] turns the panel on; when the activity
 *   resumes, an unsecured (swipe-only) lock screen is dismissed. A secured
 *   lock screen stays in front (F-SCR-13 is opt-in and not built yet).
 */
object ScreenWindow {
    fun attach(activity: Activity, scope: CoroutineScope) {
        clearUnsecuredLock(activity)
        scope.launch {
            TabulaStatus.keepScreenOn.collect { keep ->
                if (keep) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        scope.launch {
            TabulaStatus.windowBrightness.collect { level ->
                val attributes = activity.window.attributes
                attributes.screenBrightness = level ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                activity.window.attributes = attributes
            }
        }
    }

    fun onResume(activity: Activity) = clearUnsecuredLock(activity)

    /**
     * Powering the panel off arms the lock screen even without a PIN. A
     * swipe-only one is cleared: the window may show over it (API 27+) and is
     * asked to dismiss it. A secured one is left alone.
     */
    fun clearUnsecuredLock(activity: Activity) {
        val keyguard = activity.getSystemService(KeyguardManager::class.java) ?: return
        val unsecured = !keyguard.isKeyguardSecure
        if (Build.VERSION.SDK_INT >= 27) activity.setShowWhenLocked(unsecured)
        if (Build.VERSION.SDK_INT >= 26 && unsecured && keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(activity, null)
        }
    }
}
