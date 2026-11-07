package io.horizontalsystems.bitcoinkit.demo

import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hodler.LockTimeInterval
import io.horizontalsystems.litecoinkit.LitecoinReceiveAddressType
import io.horizontalsystems.litecoinkit.LitecoinSendResult
import io.horizontalsystems.litecoinkit.LitecoinSendSource
import io.horizontalsystems.litecoinkit.mweb.MwebSendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoUiStateTest {

    private val litecoinState = DemoUiState(
        kitType = KitType.Litecoin,
        capabilities = KitType.Litecoin.capabilities,
        sendSource = LitecoinSendSource.Mweb,
        receiveAddressType = LitecoinReceiveAddressType.Mweb,
        lockTimeInterval = LockTimeInterval.month,
    )

    @Test
    fun committedTo_nonBitcoinKit_clearsLockTimeInterval() {
        val state = litecoinState.committedTo(KitType.Litecoin, Purpose.BIP44)

        assertNull(state.lockTimeInterval)
        assertFalse(state.capabilities.hodler)
    }

    @Test
    fun committedTo_nonLitecoinKit_resetsSendSourceAndReceiveAddressType() {
        val state = litecoinState.committedTo(KitType.Bitcoin, Purpose.BIP84)

        assertEquals(LitecoinSendSource.Auto, state.sendSource)
        assertEquals(LitecoinReceiveAddressType.Public, state.receiveAddressType)
        assertFalse(state.capabilities.mweb)
    }

    @Test
    fun committedTo_bitcoinKit_enablesHodlerAndMarksKitReady() {
        val state = litecoinState.committedTo(KitType.Bitcoin, Purpose.BIP84)

        assertEquals(KitType.Bitcoin, state.kitType)
        assertTrue(state.capabilities.hodler)
        assertTrue(state.kitReady)
    }

    @Test
    fun committedTo_purposeAwareKit_publishesSelectedPurpose() {
        val state = litecoinState.committedTo(KitType.Bitcoin, Purpose.BIP84)

        assertEquals(Purpose.BIP84, state.purpose)
        assertTrue(state.capabilities.purpose)
    }

    @Test
    fun committedTo_kitWithoutPurposeSupport_keepsSelectionButHidesTheSelector() {
        val state = litecoinState.committedTo(KitType.BitcoinCash, Purpose.BIP84)

        assertEquals(Purpose.BIP84, state.purpose)
        assertFalse(state.capabilities.purpose)
    }

    @Test
    fun committedTo_anotherKit_clearsSendAddressAndAmount() {
        val entered = litecoinState.copy(address = "LtcAddress", amount = 50_000)

        val state = entered.committedTo(KitType.BitcoinCash, Purpose.BIP84)

        assertEquals("", state.address)
        assertNull(state.amount)
    }

    @Test
    fun released_committedState_dropsKitDataAndReadiness() {
        val state = litecoinState.copy(networkName = "MainNet", receiveAddress = "ltc1").released()

        assertEquals("", state.networkName)
        assertEquals("", state.receiveAddress)
        assertNull(state.balance)
        assertFalse(state.kitReady)
    }

    @Test
    fun feeRate_everyPriority_matchesTheAdvertisedRate() {
        assertEquals(5000, FeePriority.Low.feeRate)
        assertEquals(7000, FeePriority.Medium.feeRate)
        assertEquals(10000, FeePriority.High.feeRate)
    }

    @Test
    fun toSendOutcome_fullTransaction_reportsSerializedTxInfo() {
        val outcome = fullTransaction("txinfo").toSendOutcome()

        assertEquals("txinfo", outcome.id)
        assertFalse(outcome.mweb)
        assertEquals("Transaction sent txinfo", outcome.message)
    }

    @Test
    fun toSendOutcome_litecoinPublicResult_reportsSerializedTxInfo() {
        val outcome = LitecoinSendResult.Public(fullTransaction("txinfo")).toSendOutcome()

        assertEquals("txinfo", outcome.id)
        assertEquals("Transaction sent txinfo", outcome.message)
    }

    @Test
    fun toSendOutcome_litecoinMwebResult_prefersCanonicalHash() {
        val result = LitecoinSendResult.Mweb(
            MwebSendResult(
                canonicalTransactionHash = "canonical",
                rawTransaction = byteArrayOf(),
                outputIds = listOf("first", "second"),
            )
        )

        val outcome = result.toSendOutcome()

        assertEquals("canonical", outcome.id)
        assertTrue(outcome.mweb)
        assertEquals("MWEB transaction sent canonical", outcome.message)
    }

    @Test
    fun toSendOutcome_litecoinMwebResultWithoutCanonicalHash_fallsBackToOutputIds() {
        val result = LitecoinSendResult.Mweb(
            MwebSendResult(
                canonicalTransactionHash = null,
                rawTransaction = byteArrayOf(),
                outputIds = listOf("first", "second"),
            )
        )

        assertEquals("first, second", result.toSendOutcome().id)
    }

    // forceHashUpdate = false keeps the constructor from serializing an empty transaction.
    private fun fullTransaction(serializedTxInfo: String) = FullTransaction(
        header = Transaction().apply { this.serializedTxInfo = serializedTxInfo },
        inputs = emptyList(),
        outputs = emptyList(),
        forceHashUpdate = false,
    )
}
