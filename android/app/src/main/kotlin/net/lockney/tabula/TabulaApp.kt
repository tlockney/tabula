package net.lockney.tabula

import android.app.Application

/** Application entry: owns the process-wide [TabulaStore]. */
class TabulaApp : Application() {
    lateinit var store: TabulaStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = TabulaStore(this)
    }
}
