package net.lockney.tabula

import android.app.admin.DeviceAdminReceiver

/**
 * Device admin component (packet P2.3). Its only policy is force-lock, which
 * the screen command uses to switch the panel off (FEATURES F-SCR-14). The
 * system requires the receiver; it handles no callbacks.
 */
class ScreenAdminReceiver : DeviceAdminReceiver()
