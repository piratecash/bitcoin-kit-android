package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MwebTransactionPreparerTest {
    private val networkType = LitecoinKit.NetworkType.MainNet
    private val addressCodec = MwebAddressCodec(networkType)
    private val transactionSerializer = BaseTransactionSerializer()
    private val mwebDestination = addressCodec.encode(ByteArray(33) { 1 }, ByteArray(33) { 2 })
    private val mwebChange = addressCodec.encode(ByteArray(33) { 3 }, ByteArray(33) { 4 })
    private val publicDestination = "ltc1qpublicrecipient"
    private val syncState = MwebSyncState(blockHeaderHeight = 100, mwebHeaderHeight = 100, mwebUtxosHeight = 100)

    @Test
    fun mwebFeeFormula_pureMwebTwoOutputs_returnsKernelPlusTwoStandardOutputsTimesBaseFee() {
        val outputs = listOf(mwebOutput(value = 1_000), mwebOutput(value = 1_000))

        val fee = MwebFeeFormula.estimate(outputs = outputs, feeRate = 1, isPegIn = false)

        // KernelWithStealth (3) + 2 * StandardOutput (18) = 39 weight units;
        // BaseMwebFee (100 sat/wu) -> 3900 sat. No canonical TxOut bytes.
        assertEquals(3_900L, fee)
    }

    @Test
    fun mwebFeeFormula_pegOutPublicAndMwebChange_addsCanonicalTxOutComponent() {
        val publicRecipient = publicOutput(value = 1_000, scriptSize = 25)
        val outputs = listOf(publicRecipient, mwebOutput(value = 1_000))

        val fee = MwebFeeFormula.estimate(outputs = outputs, feeRate = 3, isPegIn = false)

        // weight = 3 (kernel) + ceilDiv(25,42)=1 + 18 (mweb change) = 22 wu
        // mwebFeeComponent = 22 * 100 = 2200
        // canonical txOutSize = 8 + 1 + 25 = 34 bytes
        // canonicalFeeComponent = ceil(3*1000 * 34 / 1000) = 102
        // No HogEx surcharge for peg-out.
        assertEquals(2_200L + 102L, fee)
    }

    @Test
    fun mwebFeeFormula_pegInPublicChangeAndMwebRecipient_addsHogExSurcharge() {
        val mwebRecipient = mwebOutput(value = 1_000)
        val publicChange = publicOutput(value = 4_000, scriptSize = 25)
        val outputs = listOf(mwebRecipient, publicChange)

        val fee = MwebFeeFormula.estimate(outputs = outputs, feeRate = 5, isPegIn = true)

        // weight = 3 + 18 + ceilDiv(25,42)=1 = 22 wu -> 2200 sat
        // canonical txOutSize = 8 + 1 + 25 = 34 bytes -> ceil(5*1000*34/1000)=170
        // HogEx surcharge = feeRate * 41 = 5 * 41 = 205
        assertEquals(2_200L + 170L + 205L, fee)
    }

    @Test
    fun mwebFeeFormula_isMwebOutput_recognizesSixtySixByteUnknownScript() {
        val mwebLike = TransactionOutput(value = 0, index = 0, script = ByteArray(66), type = ScriptType.UNKNOWN)
        val nonMwebShortScript = TransactionOutput(value = 0, index = 0, script = ByteArray(25), type = ScriptType.UNKNOWN)
        val knownScriptType = TransactionOutput(value = 0, index = 0, script = ByteArray(66), type = ScriptType.P2WPKH)

        assertTrue(MwebFeeFormula.isMwebOutput(mwebLike))
        assertEquals(false, MwebFeeFormula.isMwebOutput(nonMwebShortScript))
        assertEquals(false, MwebFeeFormula.isMwebOutput(knownScriptType))
    }

    @Test
    fun prepare_mwebToMwebSufficientCoins_returnsLocalCanonicalFeeAndExpectedChange() {
        val confirmedUtxoValue = 100_000L
        val recipientValue = 30_000L
        val daemonClient = stubDryRunDaemon()
        val preparer = preparer(
            mwebUtxos = listOf(mwebUtxo(value = confirmedUtxoValue, height = 1)),
        )

        val prepared = preparer.prepare(
            request = MwebSendRequest.MwebToMweb(mwebDestination, recipientValue, feeRate = 1),
            publicOptions = publicOptions(),
            dryRun = daemonClient::dryRun,
        )

        // Two MWEB outputs (recipient + change) -> 39 weight units * 100 = 3900 sat.
        assertEquals(3_900L, prepared.mwebFee)
        // No public canonical inputs -> no canonical fee.
        assertEquals(0L, prepared.normalFee)
        assertEquals(confirmedUtxoValue - recipientValue - 3_900L, prepared.changeValue)
        assertEquals(1, daemonClient.dryRunCalls)
    }

    @Test
    fun prepare_mwebToMwebInsufficientCoins_throwsInsufficientFunds() {
        // Only confirmed coins below recipient + fee -> InsufficientFunds.
        val daemonClient = stubDryRunDaemon()
        val preparer = preparer(
            mwebUtxos = listOf(mwebUtxo(value = 1_000, height = 1)),
        )

        assertThrows(MwebError.InsufficientFunds::class.java) {
            preparer.prepare(
                request = MwebSendRequest.MwebToMweb(mwebDestination, 30_000L, feeRate = 1),
                publicOptions = publicOptions(),
                dryRun = daemonClient::dryRun,
            )
        }
    }

    @Test
    fun prepare_mwebToMwebPendingCoinsCoverShortfall_throwsInsufficientMwebConfirmations() {
        // Confirmed alone is not enough but unconfirmed brings the total above value+fee -> mweb confirmations error.
        val daemonClient = stubDryRunDaemon()
        val preparer = preparer(
            mwebUtxos = listOf(
                mwebUtxo(value = 5_000L, height = 1),
                mwebUtxo(value = 50_000L, height = 0),
            ),
        )

        assertThrows(MwebError.InsufficientMwebConfirmations::class.java) {
            preparer.prepare(
                request = MwebSendRequest.MwebToMweb(mwebDestination, 30_000L, feeRate = 1),
                publicOptions = publicOptions(),
                dryRun = daemonClient::dryRun,
            )
        }
    }

    @Test
    fun prepare_mwebToPublicSixConfirmations_keepsMwebFeeIndependentOfDryRunStrippedOutputs() {
        val confirmedUtxoValue = 100_000L
        val recipientValue = 30_000L
        val tipHeight = syncState.mwebUtxosHeight
        // Six confirmations: tip - height + 1 = 6 -> height = tip - 5.
        val mwebUtxo = mwebUtxo(value = confirmedUtxoValue, height = tipHeight - 5)
        val bridge = FakeBridge()
        // Daemon strips outputs for peg-out (mimics real mwebd behavior).
        val daemonClient = stubDryRunDaemon(rawTransactionFactory = ::rawTransactionWithoutOutputs)
        val preparer = preparer(
            mwebUtxos = listOf(mwebUtxo),
            bridge = bridge,
        )

        val prepared = preparer.prepare(
            request = MwebSendRequest.MwebToPublic(publicDestination, recipientValue, feeRate = 1),
            publicOptions = publicOptions(),
            dryRun = daemonClient::dryRun,
        )

        // Fee MUST come from the local formula, not from the daemon-stripped output sum.
        // weight = 3 + ceilDiv(25,42)=1 + 18 = 22 wu -> 2200 sat
        // canonical txOutSize = 8 + 1 + 25 = 34 -> 34 sat at feeRate 1
        assertEquals(2_200L + 34L, prepared.mwebFee)
        assertEquals(0L, prepared.normalFee)
        assertEquals(confirmedUtxoValue - recipientValue - prepared.mwebFee, prepared.changeValue)
        assertEquals(1, daemonClient.dryRunCalls)
        assertTrue("bridge.output must be invoked at least once", bridge.outputCalls.isNotEmpty())
    }

    @Test
    fun prepare_mwebToPublicBelowSixConfirmations_throwsInsufficientMwebConfirmations() {
        val tipHeight = syncState.mwebUtxosHeight
        val daemonClient = stubDryRunDaemon()
        val preparer = preparer(
            // 5 confirmations -> below the network-required 6.
            mwebUtxos = listOf(mwebUtxo(value = 100_000L, height = tipHeight - 4)),
            bridge = FakeBridge(),
        )

        assertThrows(MwebError.InsufficientMwebConfirmations::class.java) {
            preparer.prepare(
                request = MwebSendRequest.MwebToPublic(publicDestination, 30_000L, feeRate = 1),
                publicOptions = publicOptions(),
                dryRun = daemonClient::dryRun,
            )
        }
    }

    @Test
    fun prepare_publicToMwebSingleUtxo_addsCanonicalFeeAndHogExSurcharge() {
        val daemonClient = stubDryRunDaemon()
        val publicValue = 10_000L
        val recipientValue = 5_000L
        val preparer = preparer(
            bridge = FakeBridge(publicUtxos = listOf(publicUtxo(value = publicValue))),
        )

        val prepared = preparer.prepare(
            request = MwebSendRequest.PublicToMweb(mwebDestination, recipientValue, feeRate = 1),
            publicOptions = publicOptions(),
            dryRun = daemonClient::dryRun,
        )

        // Outputs: MWEB recipient (66B, MWEB) + public change (script size 1 in fake bridge).
        // weight = 3 + 18 + ceilDiv(1,42)=1 = 22 wu -> 2200 sat
        // canonical txOutSize = 8 + 1 + 1 = 10 bytes -> 10 sat at feeRate 1
        // HogEx surcharge = 1 * 41 = 41
        val expectedMwebFee = 2_200L + 10L + 41L
        assertEquals(expectedMwebFee, prepared.mwebFee)
        // Canonical post-daemon broadcast tx contains the public input(s) and the peg-in marker (34-byte script).
        assertTrue("normalFee must be positive for peg-in", prepared.normalFee > 0L)
        // The total fee balance must hold: input = recipient + change + total fee.
        assertEquals(
            publicValue,
            recipientValue + (prepared.changeValue ?: 0L) + prepared.normalFee + prepared.mwebFee,
        )
        assertEquals(1, prepared.selectedPublicUtxos.size)
        assertEquals(1, daemonClient.dryRunCalls)
    }

    @Test
    fun prepare_publicToMwebMultipleSmallUtxos_iteratesUntilCoverageWithGrowingFee() {
        // Two small UTXOs that individually do not cover recipient + fee.
        val daemonClient = stubDryRunDaemon()
        val recipientValue = 5_000L
        val preparer = preparer(
            bridge = FakeBridge(
                publicUtxos = listOf(
                    publicUtxo(value = 4_000),
                    publicUtxo(value = 4_000),
                ),
            ),
        )

        val prepared = preparer.prepare(
            request = MwebSendRequest.PublicToMweb(mwebDestination, recipientValue, feeRate = 1),
            publicOptions = publicOptions(),
            dryRun = daemonClient::dryRun,
        )

        // Both UTXOs should be selected after fee growth makes one insufficient.
        assertEquals(2, prepared.selectedPublicUtxos.size)
        assertTrue("Should produce non-zero MWEB fee", prepared.mwebFee > 0L)
        assertTrue("Should produce non-zero canonical fee", prepared.normalFee > 0L)
        // After convergence, dryRun is called once with the final template.
        assertEquals(1, daemonClient.dryRunCalls)
    }

    @Test
    fun prepare_publicToMwebInsufficientPublicUtxos_throwsInsufficientFunds() {
        val daemonClient = stubDryRunDaemon()
        val preparer = preparer(
            bridge = FakeBridge(publicUtxos = listOf(publicUtxo(value = 1_000))),
        )

        assertThrows(MwebError.InsufficientFunds::class.java) {
            preparer.prepare(
                request = MwebSendRequest.PublicToMweb(mwebDestination, 5_000, feeRate = 1),
                publicOptions = publicOptions(),
                dryRun = daemonClient::dryRun,
            )
        }
    }

    @Test
    fun prepare_dryRunCalledOnceWithFinalTemplate_passesPreparerOutputs() {
        val daemonClient = stubDryRunDaemon()
        val preparer = preparer(
            mwebUtxos = listOf(mwebUtxo(value = 100_000, height = 1)),
        )

        val prepared = preparer.prepare(
            request = MwebSendRequest.MwebToMweb(mwebDestination, 30_000, feeRate = 1),
            publicOptions = publicOptions(),
            dryRun = daemonClient::dryRun,
        )

        assertEquals(1, daemonClient.dryRunCalls)
        // The dry-run was invoked with exactly the prepared template, allowing daemon-side validation.
        assertTrue(daemonClient.lastDryRunTemplate.contentEquals(prepared.rawTemplate))
    }

    private fun preparer(
        mwebUtxos: List<MwebUtxo> = emptyList(),
        bridge: MwebPublicTransactionBridge? = null,
    ): MwebTransactionPreparer {
        return MwebTransactionPreparer(
            addressCodec = addressCodec,
            publicTransactionBridge = bridge,
            changeAddressProvider = { mwebChange },
            syncStateProvider = { syncState },
            utxosProvider = { mwebUtxos },
            transactionSerializer = transactionSerializer,
        )
    }

    private fun stubDryRunDaemon(
        rawTransactionFactory: (ByteArray) -> ByteArray = { it },
    ): StubDaemon = StubDaemon(rawTransactionFactory)

    private fun mwebOutput(value: Long): TransactionOutput {
        return TransactionOutput(value = value, index = 0, script = ByteArray(66), type = ScriptType.UNKNOWN)
    }

    private fun publicOutput(value: Long, scriptSize: Int): TransactionOutput {
        return TransactionOutput(value = value, index = 0, script = ByteArray(scriptSize), type = ScriptType.P2PKH)
    }

    private fun mwebUtxo(value: Long, height: Int): MwebUtxo {
        return MwebUtxo(
            outputId = "00".repeat(32),
            address = mwebDestination,
            addressIndex = 1,
            value = value,
            height = height,
            blockTime = if (height > 0) 1_000L else 0L,
            spent = false,
        )
    }

    private fun publicOptions(): MwebPublicSendOptions {
        return MwebPublicSendOptions(
            unspentOutputs = null,
            changeToFirstInput = false,
            rbfEnabled = false,
            filters = UtxoFilters(),
        )
    }

    private fun publicUtxo(value: Long): UnspentOutput {
        val transaction = Transaction(version = 2, lockTime = 0).apply {
            hash = ByteArray(32) { (value.toInt() + it + 1).toByte() }
        }
        val publicKey = PublicKey(
            account = 0,
            index = 0,
            external = true,
            publicKey = ByteArray(33) { 2 },
            publicKeyHash = ByteArray(20) { 3 },
        )
        val output = TransactionOutput(
            value = value,
            index = 0,
            script = byteArrayOf(0),
            type = ScriptType.P2WPKH,
            address = "public-source",
            publicKey = publicKey,
        ).apply {
            transactionHash = transaction.hash
        }
        return UnspentOutput(
            output = output,
            publicKey = publicKey,
            transaction = transaction,
            block = null,
        )
    }

    private fun rawTransactionWithoutOutputs(template: ByteArray): ByteArray {
        return transactionSerializer.serialize(
            FullTransaction(
                header = Transaction(version = 2, lockTime = 0).apply { extraPayload = byteArrayOf(1) },
                inputs = emptyList(),
                outputs = emptyList(),
                transactionSerializer = transactionSerializer,
            )
        )
    }

    private class StubDaemon(
        private val rawTransactionFactory: (ByteArray) -> ByteArray,
    ) {
        var dryRunCalls: Int = 0
            private set
        var lastDryRunTemplate: ByteArray = ByteArray(0)
            private set

        fun dryRun(rawTemplate: ByteArray, feeRate: Int): ByteArray {
            dryRunCalls += 1
            lastDryRunTemplate = rawTemplate.copyOf()
            return rawTransactionFactory(rawTemplate)
        }
    }

    private class FakeBridge(
        private val publicUtxos: List<UnspentOutput> = emptyList(),
    ) : MwebPublicTransactionBridge {
        val outputCalls = mutableListOf<String>()

        override fun spendableUtxos(options: MwebPublicSendOptions): List<UnspentOutput> = publicUtxos

        override fun output(value: Long, address: String): TransactionOutput {
            outputCalls.add(address)
            return TransactionOutput(
                value = value,
                index = 0,
                script = ByteArray(25),
                type = ScriptType.P2PKH,
                address = address,
            )
        }

        override fun changeOutput(
            value: Long,
            selectedUtxos: List<UnspentOutput>,
            changeToFirstInput: Boolean,
        ): TransactionOutput {
            return TransactionOutput(
                value = value,
                index = 0,
                script = byteArrayOf(0),
                type = ScriptType.P2WPKH,
                address = "public-change",
            )
        }

        override fun serialize(transaction: FullTransaction): ByteArray {
            return ByteArray(0)
        }

        override fun processRelayed(transaction: FullTransaction): FullTransaction = transaction

        override suspend fun sign(rawTransaction: ByteArray, selectedUtxos: List<UnspentOutput>): FullTransaction {
            throw UnsupportedOperationException("not used in preparer tests")
        }
    }
}
