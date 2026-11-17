package io.horizontalsystems.bitcoinkit.demo

import android.app.Application
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Logger.setLogWriters(platformLogWriter())
            Logger.setMinSeverity(Severity.Debug)
        } else {
            Logger.setLogWriters(emptyList())
        }

        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }

}
