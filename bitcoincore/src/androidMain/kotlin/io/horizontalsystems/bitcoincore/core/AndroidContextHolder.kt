package io.horizontalsystems.bitcoincore.core

import android.content.Context
import androidx.startup.Initializer

private object BitcoinCoreAppContextHolder {
    @Volatile
    var appContext: Context? = null
}

// Populated by BitcoinCoreContextInitializer at app startup, before any database is opened.
@PublishedApi
internal val appContext: Context
    get() = checkNotNull(BitcoinCoreAppContextHolder.appContext) {
        "BitcoinCoreContextInitializer has not run yet"
    }

/** Every database open needs this; an app that strips androidx.startup from its manifest breaks them. */
class BitcoinCoreContextInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        BitcoinCoreAppContextHolder.appContext = context.applicationContext
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
