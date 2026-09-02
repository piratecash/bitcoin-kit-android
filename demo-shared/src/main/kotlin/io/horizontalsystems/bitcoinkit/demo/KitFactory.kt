package io.horizontalsystems.bitcoinkit.demo

import cash.p.dogecoinkit.DogecoinKit
import io.horizontalsystems.bitcoincash.BitcoinCashKit
import io.horizontalsystems.bitcoincash.MainNetBitcoinCash
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoinkit.BitcoinKit
import io.horizontalsystems.cosantakit.CosantaKit
import io.horizontalsystems.dashkit.DashKit
import io.horizontalsystems.ecash.ECashKit
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.CoroutineMwebDispatcherProvider
import io.horizontalsystems.litecoinkit.mweb.MwebConfig
import io.horizontalsystems.piratecashkit.PirateCashKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Builds and clears the eight kits. Each kit derives its database name from its own network type
 * plus the shared wallet id — and, where the kit supports it, the derivation purpose — so neither a
 * shared wallet id nor a purpose switch can make two kits collide.
 */
object KitFactory {

    const val WALLET_ID = "MyWallet"

    private const val CONFIRMATIONS_THRESHOLD = 3
    private const val PASSPHRASE = ""

    private val bitcoinNetwork = BitcoinKit.NetworkType.MainNet
    private val bitcoinCashNetwork = BitcoinCashKit.NetworkType.MainNet(MainNetBitcoinCash.CoinType.Type145)
    private val eCashNetwork = ECashKit.NetworkType.MainNet
    private val litecoinNetwork = LitecoinKit.NetworkType.MainNet
    private val dogecoinNetwork = DogecoinKit.NetworkType.MainNet
    private val dashNetwork = DashKit.NetworkType.MainNet
    private val cosantaNetwork = CosantaKit.NetworkType.MainNet
    private val pirateCashNetwork = PirateCashKit.NetworkType.MainNet

    fun create(
        type: KitType,
        purpose: Purpose,
        dataDir: String,
        mwebDataDir: String,
        connectionManager: IConnectionManager,
        words: List<String>,
        walletId: String,
        scope: CoroutineScope,
        listener: DemoKitListener,
    ): DemoKit {
        val syncMode = BitcoinCore.SyncMode.Blockchair()
        val kit = when (type) {
            // Only Bitcoin and Litecoin derive by purpose; the others have a single address scheme.
            KitType.Bitcoin -> BitcoinKit(
                dataDir = dataDir,
                connectionManager = connectionManager,
                words = words,
                passphrase = PASSPHRASE,
                walletId = walletId,
                networkType = bitcoinNetwork,
                syncMode = syncMode,
                confirmationsThreshold = CONFIRMATIONS_THRESHOLD,
                purpose = purpose,
            )

            KitType.BitcoinCash -> BitcoinCashKit(
                dataDir = dataDir,
                connectionManager = connectionManager,
                words = words,
                passphrase = PASSPHRASE,
                walletId = walletId,
                networkType = bitcoinCashNetwork,
                syncMode = syncMode,
                confirmationsThreshold = CONFIRMATIONS_THRESHOLD,
            )

            KitType.ECash -> ECashKit(
                dataDir = dataDir,
                connectionManager = connectionManager,
                words = words,
                passphrase = PASSPHRASE,
                walletId = walletId,
                networkType = eCashNetwork,
                syncMode = syncMode,
                confirmationsThreshold = CONFIRMATIONS_THRESHOLD,
            )

            KitType.Litecoin -> LitecoinKit(
                dataDir = dataDir,
                mwebDataDir = mwebDataDir,
                connectionManager = connectionManager,
                words = words,
                passphrase = PASSPHRASE,
                walletId = walletId,
                networkType = litecoinNetwork,
                syncMode = syncMode,
                confirmationsThreshold = CONFIRMATIONS_THRESHOLD,
                purpose = purpose,
                mwebConfig = MwebConfig(
                    dispatcherProvider = CoroutineMwebDispatcherProvider(Dispatchers.IO),
                    daemonClientFactory = DemoMwebDaemonClientFactory(),
                ),
            )

            KitType.Dogecoin -> DogecoinKit(
                dataDir = dataDir,
                connectionManager = connectionManager,
                words = words,
                passphrase = PASSPHRASE,
                walletId = walletId,
                networkType = dogecoinNetwork,
                syncMode = syncMode,
                confirmationsThreshold = CONFIRMATIONS_THRESHOLD,
            )

            KitType.Dash -> DashKit(
                dataDir = dataDir,
                connectionManager = connectionManager,
                words = words,
                passphrase = PASSPHRASE,
                walletId = walletId,
                networkType = dashNetwork,
                syncMode = syncMode,
                confirmationsThreshold = CONFIRMATIONS_THRESHOLD,
            )

            KitType.Cosanta -> CosantaKit(
                dataDir = dataDir,
                connectionManager = connectionManager,
                words = words,
                passphrase = PASSPHRASE,
                walletId = walletId,
                networkType = cosantaNetwork,
                syncMode = syncMode,
                confirmationsThreshold = CONFIRMATIONS_THRESHOLD,
            )

            KitType.PirateCash -> PirateCashKit(
                dataDir = dataDir,
                connectionManager = connectionManager,
                words = words,
                passphrase = PASSPHRASE,
                walletId = walletId,
                networkType = pirateCashNetwork,
                syncMode = syncMode,
                confirmationsThreshold = CONFIRMATIONS_THRESHOLD,
            )
        }

        return DemoKit(
            type = type,
            kit = kit,
            dataDir = dataDir,
            mwebDataDir = mwebDataDir,
            walletId = walletId,
            scope = scope,
            listener = listener,
        )
    }

    /**
     * Deletes the kit's storage and reports what survived. The kits delete through
     * `File.delete()` without checking the result, so a filesystem that refuses to unlink an open
     * file wipes nothing and says nothing; an empty list is the only proof the wipe happened.
     */
    fun clear(type: KitType, dataDir: String, mwebDataDir: String, walletId: String): List<String> {
        when (type) {
            KitType.Bitcoin -> BitcoinKit.clear(dataDir, bitcoinNetwork, walletId)
            KitType.BitcoinCash -> BitcoinCashKit.clear(dataDir, bitcoinCashNetwork, walletId)
            KitType.ECash -> ECashKit.clear(dataDir, eCashNetwork, walletId)
            KitType.Litecoin -> LitecoinKit.clear(dataDir, mwebDataDir, litecoinNetwork, walletId)
            KitType.Dogecoin -> DogecoinKit.clear(dataDir, dogecoinNetwork, walletId)
            KitType.Dash -> DashKit.clear(dataDir, dashNetwork, walletId)
            KitType.Cosanta -> CosantaKit.clear(dataDir, cosantaNetwork, walletId)
            KitType.PirateCash -> PirateCashKit.clear(dataDir, pirateCashNetwork, walletId)
        }

        val prefix = databasePrefix(type)
        val directories = if (type == KitType.Litecoin) listOf(dataDir, mwebDataDir) else listOf(dataDir)
        return directories.flatMap { survivors(it, prefix) }
    }

    private fun survivors(directory: String, prefix: String): List<String> {
        val file = File(directory)
        if (!file.exists()) return emptyList()
        val names = file.list() ?: return listOf("$directory (not listable)")
        return names.filter { it.startsWith(prefix) }.map { "$directory/$it" }
    }

    private fun databasePrefix(type: KitType) = when (type) {
        KitType.Bitcoin -> "Bitcoin-"
        KitType.BitcoinCash -> "BitcoinCash-"
        KitType.ECash -> "ECash-"
        KitType.Litecoin -> "Litecoin-"
        KitType.Dogecoin -> "Dogecoin-"
        KitType.Dash -> "Dash-"
        KitType.Cosanta -> "Cosanta-"
        KitType.PirateCash -> "PirateCash-"
    }
}
