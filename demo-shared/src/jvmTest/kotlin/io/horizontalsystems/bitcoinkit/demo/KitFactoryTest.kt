package io.horizontalsystems.bitcoinkit.demo

import cash.p.dogecoinkit.DogecoinKit
import io.horizontalsystems.bitcoincash.BitcoinCashKit
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IConnectionManagerListener
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.BlockInfo
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.bitcoinkit.BitcoinKit
import io.horizontalsystems.cosantakit.CosantaKit
import io.horizontalsystems.dashkit.DashKit
import io.horizontalsystems.ecash.ECashKit
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.MwebBalance
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo
import io.horizontalsystems.piratecashkit.PirateCashKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KitFactoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val words = "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    @Test
    fun create_everyKitType_buildsTheExpectedKitClass() {
        val expected = mapOf(
            KitType.Bitcoin to BitcoinKit::class.java,
            KitType.BitcoinCash to BitcoinCashKit::class.java,
            KitType.ECash to ECashKit::class.java,
            KitType.Litecoin to LitecoinKit::class.java,
            KitType.Dogecoin to DogecoinKit::class.java,
            KitType.Dash to DashKit::class.java,
            KitType.Cosanta to CosantaKit::class.java,
            KitType.PirateCash to PirateCashKit::class.java,
        )

        expected.forEach { (type, kitClass) ->
            withKit(type, Purpose.BIP44) { assertEquals(kitClass, it.kit.javaClass) }
        }
    }

    @Test
    fun create_everyKitType_buildsTheExpectedMainNet() {
        val expected = mapOf(
            KitType.Bitcoin to "MainNet",
            KitType.BitcoinCash to "MainNetBitcoinCash",
            KitType.ECash to "MainNetECash",
            KitType.Litecoin to "MainNetLitecoin",
            KitType.Dogecoin to "MainNetDogecoin",
            KitType.Dash to "MainNetDash",
            KitType.Cosanta to "MainNetCosanta",
            KitType.PirateCash to "MainNetPirateCash",
        )

        expected.forEach { (type, networkName) ->
            withKit(type, Purpose.BIP44) { assertEquals(networkName, it.networkName) }
        }
    }

    @Test
    fun capabilities_everyKitType_reportsHodlerMwebAndPurposePerKit() {
        KitType.entries.forEach { type ->
            withKit(type, Purpose.BIP44) {
                assertEquals(type == KitType.Bitcoin, it.capabilities.hodler)
                assertEquals(type == KitType.Litecoin, it.capabilities.mweb)
                val purposeAware = type == KitType.Bitcoin || type == KitType.Litecoin
                assertEquals(purposeAware, it.capabilities.purpose)
            }
        }
    }

    @Test
    fun create_bitcoinWithEachPurpose_derivesTheMatchingReceiveAddress() {
        val expected = mapOf(
            Purpose.BIP44 to "1",
            Purpose.BIP49 to "3",
            Purpose.BIP84 to "bc1q",
            Purpose.BIP86 to "bc1p",
        )

        expected.forEach { (purpose, prefix) ->
            withKit(KitType.Bitcoin, purpose) {
                val address = it.receiveAddress(mweb = false)
                assertTrue("$purpose derived $address", address.startsWith(prefix))
            }
        }
    }

    @Test
    fun mwebState_litecoin_isAvailableOnlyForLitecoin() {
        withKit(KitType.Litecoin, Purpose.BIP44) { assertTrue(it.mwebState != null) }
        withKit(KitType.Bitcoin, Purpose.BIP44) { assertTrue(it.mwebState == null) }
    }

    private fun withKit(type: KitType, purpose: Purpose, block: (DemoKit) -> Unit) {
        val demoKit = KitFactory.create(
            type = type,
            purpose = purpose,
            dataDir = temporaryFolder.newFolder("data-$type-$purpose").absolutePath,
            mwebDataDir = temporaryFolder.newFolder("mweb-$type-$purpose").absolutePath,
            connectionManager = OfflineConnectionManager(),
            words = words.split(" "),
            walletId = KitFactory.WALLET_ID,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            listener = SilentListener(),
        )
        try {
            block(demoKit)
        } finally {
            demoKit.dispose()
        }
    }

    private class OfflineConnectionManager : IConnectionManager {
        override val isConnected = false
        override fun addListener(listener: IConnectionManagerListener) = Unit
        override fun removeListener(listener: IConnectionManagerListener) = Unit
        override fun onEnterForeground() = Unit
        override fun onEnterBackground() = Unit
    }

    private class SilentListener : DemoKitListener {
        override fun onTransactionsUpdate(kit: DemoKit, inserted: List<TransactionInfo>, updated: List<TransactionInfo>) = Unit
        override fun onTransactionsDelete(kit: DemoKit, hashes: List<String>) = Unit
        override fun onBalanceUpdate(kit: DemoKit, balance: BalanceInfo) = Unit
        override fun onLastBlockInfoUpdate(kit: DemoKit, blockInfo: BlockInfo) = Unit
        override fun onKitStateUpdate(kit: DemoKit, state: BitcoinCore.KitState) = Unit
        override fun onMwebBalanceUpdate(kit: DemoKit, balance: MwebBalance) = Unit
        override fun onMwebSyncStateUpdate(kit: DemoKit, state: MwebSyncState) = Unit
        override fun onMwebUtxosUpdate(kit: DemoKit, utxos: List<MwebUtxo>) = Unit
    }
}
