package io.horizontalsystems.bitcoinkit.demo

import android.app.Application
import timber.log.Timber

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }

}
