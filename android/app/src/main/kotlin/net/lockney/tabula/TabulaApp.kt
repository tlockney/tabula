package net.lockney.tabula

import android.app.Application
import net.lockney.tabula.ha.OkHttpExchange
import net.lockney.tabula.ha.TokenRefresher

/**
 * Application entry: owns the process-wide [TabulaStore], [DashboardBus] and
 * [tokens]. One [TokenRefresher] serves the TLP session and the dashboard's
 * external-auth bridge, so both share one access-token cache instead of
 * minting their own.
 */
class TabulaApp : Application() {
    lateinit var store: TabulaStore
        private set

    /** The dashboard handle bus, shared by the service's commands and the activity. */
    val dashboard = DashboardBus()

    /** The process-wide token cache (Keystore-held refresh token, 30-minute access tokens). */
    val tokens = TokenRefresher(OkHttpExchange())

    override fun onCreate() {
        super.onCreate()
        store = TabulaStore(this)
    }
}
