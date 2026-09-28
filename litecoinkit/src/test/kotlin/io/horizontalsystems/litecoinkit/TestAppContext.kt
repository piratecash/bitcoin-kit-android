package io.horizontalsystems.litecoinkit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.core.BitcoinCoreContextInitializer

// Robolectric does not run androidx.startup initializers, so tests seed the context themselves.
internal fun testAppContext(): Context =
    ApplicationProvider.getApplicationContext<Context>().also { BitcoinCoreContextInitializer().create(it) }

// Any database name resolves to the same directory; only the parent is used.
internal val Context.testDataDir: String
    get() = requireNotNull(getDatabasePath("probe").parent)

internal val Context.testMwebDataDir: String
    get() = noBackupFilesDir.absolutePath
