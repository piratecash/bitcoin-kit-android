package io.horizontalsystems.litecoinkit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.bitcoincore.blocks.BlockDownload
import io.horizontalsystems.bitcoincore.blocks.InitialBlockDownload
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.litecoinkit.mweb.CoroutineMwebDispatcherProvider
import io.horizontalsystems.litecoinkit.mweb.MwebConfig
import io.horizontalsystems.litecoinkit.mweb.MwebError
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressCodec
import io.horizontalsystems.litecoinkit.mweb.daemon.MissingMwebDaemonClientFactory
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LitecoinKitMwebApiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val walletIds = mutableListOf<String>()
    private val kits = mutableListOf<LitecoinKit>()
    private val ioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val dispatcherProvider = CoroutineMwebDispatcherProvider(io = ioDispatcher)

    @After
    fun tearDown() {
        kits.forEach { kit -> kit.dispose() }
        walletIds.forEach { walletId ->
            LitecoinKit.clear(context, LitecoinKit.NetworkType.MainNet, walletId)
        }
        ioDispatcher.close()
    }

    @Test
    fun receiveAddress_mwebDisabled_throwsNativeUnavailable() {
        val kit = litecoinKit()

        assertThrows(MwebError.NativeUnavailable::class.java) {
            kit.receiveAddress(LitecoinReceiveAddressType.Mweb)
        }
    }

    @Test
    fun isMwebAddress_mwebMainnetAddress_returnsTrue() {
        val kit = litecoinKit()

        assertTrue(kit.isMwebAddress(mwebAddress()))
    }

    @Test
    fun isMwebAddress_publicAddress_returnsFalse() {
        val kit = litecoinKit()

        assertFalse(kit.isMwebAddress("ltc1q9z5mzd0k72k8f8g9cny70a4rvv7ne48x336jw5"))
    }

    @Test
    fun clear_mwebEnabledKitActive_throwsBeforeWipe() {
        val walletId = walletId()
        litecoinKit(walletId = walletId, mwebConfig = mwebConfig())

        assertThrows(IllegalStateException::class.java) {
            LitecoinKit.clear(context, LitecoinKit.NetworkType.MainNet, walletId)
        }
    }

    @Test
    fun clearMweb_publicKitActive_doesNotClearPublicData() {
        val walletId = walletId()
        litecoinKit(walletId = walletId)

        LitecoinKit.clearMweb(context, LitecoinKit.NetworkType.MainNet, walletId)
    }

    @Test
    fun sendInfo_publicToMwebWithoutMwebEngine_usesPublicFundingPath() {
        val kit = litecoinKit()

        assertThrows(MwebError.InsufficientFunds::class.java) {
            kit.sendInfo(
                value = 1_000,
                address = mwebAddress(),
                memo = null,
                source = LitecoinSendSource.Public,
                feeRate = 1,
                unspentOutputs = null,
                changeToFirstInput = false,
                filters = UtxoFilters(),
            )
        }
    }

    @Test
    fun sendInfo_mwebToPublicWithoutMwebEngine_throwsNativeUnavailable() {
        val kit = litecoinKit()

        assertThrows(MwebError.NativeUnavailable::class.java) {
            kit.sendInfo(
                value = 1_000,
                address = "ltc1q9z5mzd0k72k8f8g9cny70a4rvv7ne48x336jw5",
                memo = null,
                source = LitecoinSendSource.Mweb,
                feeRate = 1,
                unspentOutputs = null,
                changeToFirstInput = false,
                filters = UtxoFilters(),
            )
        }
    }

    @Test
    fun constructor_blockchairBip84Mode_requestsUnknownBlocks() {
        val kit = litecoinKit(
            syncMode = SyncMode.Blockchair(),
            purpose = Purpose.BIP84,
        )

        val blockDownload = kit.bitcoinCoreForTest().initialDownload as BlockDownload

        assertTrue(blockDownload.requestUnknownBlocks())
    }

    @Test
    fun constructor_apiBip84Mode_usesInitialBlockDownload() {
        val kit = litecoinKit(
            syncMode = SyncMode.Api(),
            purpose = Purpose.BIP84,
        )

        assertTrue(kit.bitcoinCoreForTest().initialDownload is InitialBlockDownload)
    }

    private fun litecoinKit(
        walletId: String = walletId(),
        syncMode: SyncMode = LitecoinKit.defaultSyncMode,
        purpose: Purpose = Purpose.BIP44,
        mwebConfig: MwebConfig? = null,
    ): LitecoinKit {
        val kit = LitecoinKit(
            context = context,
            seed = ByteArray(32),
            walletId = walletId,
            syncMode = syncMode,
            purpose = purpose,
            mwebConfig = mwebConfig,
        )
        kits.add(kit)
        return kit
    }

    private fun mwebConfig(): MwebConfig {
        return MwebConfig(
            dispatcherProvider = dispatcherProvider,
            daemonClientFactory = MissingMwebDaemonClientFactory,
        )
    }

    private fun mwebAddress(): String {
        return MwebAddressCodec(LitecoinKit.NetworkType.MainNet).encode(
            scanPublicKey = ByteArray(33) { (it + 1).toByte() },
            spendPublicKey = ByteArray(33) { (it + 34).toByte() },
        )
    }

    private fun walletId(): String {
        return "litecoin-mweb-api-${System.nanoTime()}".also(walletIds::add)
    }

    private fun BlockDownload.requestUnknownBlocks(): Boolean {
        val field = BlockDownload::class.java.getDeclaredField("requestUnknownBlocks")
        field.isAccessible = true
        return field.getBoolean(this)
    }

    private fun LitecoinKit.bitcoinCoreForTest(): BitcoinCore {
        val field = LitecoinKit::class.java.getDeclaredField("bitcoinCore")
        field.isAccessible = true
        return field.get(this) as BitcoinCore
    }
}
