package io.horizontalsystems.bitcoincore.rbf

import io.horizontalsystems.bitcoincore.DustCalculator
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.core.PluginManager
import io.horizontalsystems.bitcoincore.managers.PublicKeyManager
import io.horizontalsystems.bitcoincore.managers.UnspentOutputProvider
import io.horizontalsystems.bitcoincore.models.Address
import io.horizontalsystems.bitcoincore.models.AddressType
import io.horizontalsystems.bitcoincore.models.LegacyAddress
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.SegWitV0Address
import io.horizontalsystems.bitcoincore.models.TaprootAddress
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionMetadata
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.models.TransactionType
import io.horizontalsystems.bitcoincore.rbf.ReplacementTransactionBuilder.BuildError
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransactionInfo
import io.horizontalsystems.bitcoincore.storage.InputWithPreviousOutput
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.transactions.TransactionConflictsResolver
import io.horizontalsystems.bitcoincore.transactions.TransactionSizeCalculator
import io.horizontalsystems.bitcoincore.transactions.builder.LockTimeSetter
import io.horizontalsystems.bitcoincore.transactions.builder.MutableTransaction
import io.horizontalsystems.bitcoincore.transactions.extractors.TransactionMetadataExtractor
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.bitcoincore.utils.IAddressConverter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ReplacementTransactionBuilderTest {

    private val storage = mock<IStorage>()
    private val unspentOutputProvider = mock<UnspentOutputProvider>()
    private val publicKeyManager = mock<IPublicKeyManager>()
    private val sizeCalculator = TransactionSizeCalculator()
    private val serializer = BaseTransactionSerializer()
    private val addressConverter = mock<IAddressConverter>()
    private val changeKey = PublicKey().apply {
        path = NEW_CHANGE_PATH
        external = false
    }
    private val changeAddress = SegWitV0Address("new-change-address", ByteArray(20) { 7 }, AddressType.PubKeyHash)

    private val builder = newBuilder(ScriptType.P2WPKH)

    private fun newBuilder(changeScriptType: ScriptType) = ReplacementTransactionBuilder(
        storage = storage,
        sizeCalculator = sizeCalculator,
        dustCalculator = DustCalculator(DUST_RELAY_TX_FEE, sizeCalculator),
        metadataExtractor = mock<TransactionMetadataExtractor>(),
        pluginManager = mock<PluginManager>(),
        unspentOutputProvider = unspentOutputProvider,
        publicKeyManager = publicKeyManager,
        conflictsResolver = mock<TransactionConflictsResolver>(),
        transactionSerializer = serializer,
        lockTimeSetter = mock<LockTimeSetter>(),
        transactionVersion = 2,
        addressConverter = addressConverter,
        changeScriptType = changeScriptType
    )

    private val cancel = ReplacementType.Cancel(
        LegacyAddress("address", ByteArray(20), AddressType.PubKeyHash),
        PublicKey()
    )

    @Test
    fun replacementInfo_qaTransaction_rangeStartsAboveOriginalFee() {
        givenQaTransaction()

        assertEquals(QA_FLOOR..1306L, speedUpFeeRange())
    }

    @Test
    fun replacementInfo_floorAboveEverythingSpendable_returnsNull() {
        givenOriginal(inputs = QA_INPUTS, recipient = QA_RECIPIENT + QA_CHANGE)

        assertNull(builder.replacementInfo(TX_HASH, ReplacementType.SpeedUp))
    }

    @Test
    fun replacementInfo_spareUtxoCannotCoverOwnSize_returnsNull() {
        givenOriginal(inputs = TEN_INPUTS, recipient = 9000, spareUtxo = listOf(750))

        assertNull(builder.replacementInfo(TX_HASH, ReplacementType.SpeedUp))
    }

    @Test
    fun replacementInfo_spareUtxoCostsMoreThanItAdds_rangeEndsBeforeIt() {
        givenTenInputsWithCheapSpareUtxo()

        assertEquals(1753L..1754L, speedUpFeeRange())
    }

    @Test
    fun replacementInfo_spareUtxosWorthTheirInputs_rangeIncludesThem() {
        givenOriginal(inputs = TEN_INPUTS, recipient = 8246, change = 754, spareUtxo = listOf(5000, 30))

        assertEquals(1753L..6784L, speedUpFeeRange())
    }

    @Test
    fun replacementInfo_speedUpRange_bothBoundsBuild() {
        givenTenInputsWithCheapSpareUtxo()
        val range = speedUpFeeRange()

        assertEquals(1754L, speedUp(range.first).fee)
        assertEquals(1754L, speedUp(range.last).fee)
    }

    @Test
    fun replacementInfo_sentToSelf_rangeKeepsLargestOutput() {
        givenOriginal(QA_INPUTS, QA_RECIPIENT, QA_CHANGE, recipientPath = "0/0/1")

        assertEquals(QA_FLOOR..1306L, speedUpFeeRange())
    }

    @Test
    fun replacementInfo_cancel_rangeEndsAtEverythingSpendable() {
        givenOriginal(inputs = QA_INPUTS, recipient = QA_RECIPIENT + QA_CHANGE, spareUtxo = listOf(500))

        assertEquals(1077L..2517L, cancelFeeRange())
    }

    @Test
    fun replacementInfo_cancelFloorAboveEverythingSpendable_returnsNull() {
        givenOriginal(inputs = listOf(900), recipient = 600)

        assertNull(builder.replacementInfo(TX_HASH, cancel))
    }

    @Test
    fun replacementTransaction_feeAboveRange_throwsUnableToReplace() {
        givenTenInputsWithCheapSpareUtxo()

        assertThrows(BuildError.UnableToReplace::class.java) { speedUp(minFee = 1755) }
    }

    @Test
    fun replacementTransaction_minFeeEqualsOriginalFee_throwsFeeTooLow() {
        givenQaTransaction()

        assertThrows(BuildError.FeeTooLow::class.java) { speedUp(minFee = QA_FEE) }
    }

    @Test
    fun replacementTransaction_minFeeOneBelowFloor_throwsFeeTooLow() {
        givenQaTransaction()

        assertThrows(BuildError.FeeTooLow::class.java) { speedUp(minFee = QA_FLOOR - 1) }
    }

    @Test
    fun replacementTransaction_qaCaseAtFloor_dropsDustChangeKeepsRecipient() {
        givenQaTransaction()

        val replacement = speedUp(speedUpFeeRange().first)

        assertEquals(1306L, replacement.fee)
        assertEquals(listOf(QA_RECIPIENT), replacement.outputs.map { it.value })
    }

    @Test
    fun replacementTransaction_largeChange_reducesChangeToExactFloor() {
        givenOriginal(inputs = listOf(10_000, 5_000, 3_000), recipient = QA_RECIPIENT, change = 15_912)

        val replacement = speedUp(speedUpFeeRange().first)

        assertEquals(QA_FLOOR, replacement.fee)
        assertEquals(QA_RECIPIENT, replacement.recipientOutputValue)
        assertEquals(15_635L, replacement.changeOutputValue)
    }

    @Test
    fun replacementTransaction_extraInputGrowsSize_feeCoversLargerSize() {
        givenOriginal(inputs = listOf(1349, 1003, 310), recipient = QA_RECIPIENT, change = 574, spareUtxo = listOf(300))

        val replacement = speedUp(QA_FLOOR)

        assertEquals(4, replacement.inputsToSign.size)
        assertEquals(4L * 345, replacement.fee)
        assertEquals(QA_RECIPIENT, replacement.recipientOutputValue)
        assertEquals(325L, replacement.changeOutputValue)
    }

    @Test
    fun replacementTransaction_extraInputCostsMoreThanDroppingChange_picksCheaperCandidate() {
        givenOriginal(inputs = TEN_INPUTS, recipient = 8200, change = 800, spareUtxo = listOf(400))

        val replacement = speedUp(1753)

        assertEquals(1800L, replacement.fee)
        assertEquals(10, replacement.inputsToSign.size)
    }

    @Test
    fun replacementTransaction_requestedFeeAboveFeeRateFloor_notLowered() {
        givenOriginal(inputs = listOf(1775, 1003, 310), recipient = QA_RECIPIENT, change = 1000, spareUtxo = listOf(100))

        val replacement = speedUp(1600)

        assertEquals(1600L, replacement.fee)
        assertEquals(331L, replacement.changeOutputValue)
    }

    @Test
    fun replacementTransaction_originalRateExceedsFloor_paysAboveOriginalRate() {
        givenOriginal(inputs = listOf(2924, 1003, 500), recipient = QA_RECIPIENT, change = 400, spareUtxo = listOf(1000))

        val replacement = speedUp(3300)

        assertEquals(11L * 345, replacement.fee)
        assertEquals(375L, replacement.changeOutputValue)
        assertFeeRateAbove(replacement, originalFee = 2770, originalSize = 277)
    }

    @Test
    fun replacementTransaction_spareUtxoAtOriginalRate_paysStrictlyHigherRate() {
        val taprootAddress = TaprootAddress("taproot-change-address", ByteArray(32) { 7 }, 1)
        val taprootChangeBuilder = newBuilder(ScriptType.P2TR)
        givenOriginal(inputs = listOf(11_110), recipient = 10_000, spareUtxo = listOf(100_000), scriptType = ScriptType.P2TR)
        whenever(addressConverter.convert(changeKey, ScriptType.P2TR)).thenReturn(taprootAddress)

        val replacement = taprootChangeBuilder.replacementTransaction(TX_HASH, 1221, ReplacementType.SpeedUp).first

        assertEquals(212L, replacement.size)
        assertEquals(11L * 212, replacement.fee)
        assertNewChangeOutput(replacement, value = 98_778, taprootAddress)
        assertFeeRateAbove(replacement, originalFee = 1110, originalSize = 111)
    }

    @Test
    fun replacementTransaction_shortSignaturesRaiseRealRate_paysAboveRealRate() {
        val legacyAddress = LegacyAddress("legacy-change-address", ByteArray(20) { 7 }, AddressType.PubKeyHash)
        val legacyChangeBuilder = newBuilder(ScriptType.P2PKH)
        givenOriginal(inputs = listOf(58_499, 58_500), recipient = 100_000, spareUtxo = listOf(100_000), scriptType = ScriptType.P2PKH)
        whenever(addressConverter.convert(changeKey, ScriptType.P2PKH)).thenReturn(legacyAddress)

        val floor = requireNotNull(legacyChangeBuilder.replacementInfo(TX_HASH, ReplacementType.SpeedUp)).feeRange.first
        val replacement = legacyChangeBuilder.replacementTransaction(TX_HASH, floor, ReplacementType.SpeedUp).first

        assertEquals(17_339L, floor)
        assertEquals(522L, replacement.size)
        assertEquals(51L * 522, replacement.fee)
        assertNewChangeOutput(replacement, value = 90_377, legacyAddress)
        assertFeeRateAbove(replacement, originalFee = 16_999, originalSize = 338)
    }

    @Test
    fun replacementTransaction_witnessOriginal_marginRaisesFloor() {
        givenOriginal(inputs = listOf(3000, 2000), recipient = 1930, change = 1000)

        val replacement = speedUp(speedUpFeeRange().first)

        assertEquals(11L * 209, replacement.fee)
        assertEquals(771L, replacement.changeOutputValue)
        assertFeeRateAbove(replacement, originalFee = 2070, originalSize = 207)
    }

    @Test
    fun replacementInfo_spareUtxoBelowMarginFloor_returnsNull() {
        givenOriginal(inputs = listOf(3000, 2000), recipient = 3240, spareUtxo = listOf(800))

        assertNull(builder.replacementInfo(TX_HASH, ReplacementType.SpeedUp))
    }

    @Test
    fun replacementTransaction_peerFetchedWitnessOriginal_paysNormalFloor() {
        // The fixture stores no witness and segwit = false, as a transaction fetched from a peer does.
        givenOriginal(inputs = listOf(16_410), recipient = 10_000, change = 5000)

        val replacement = speedUp(speedUpFeeRange().first)

        assertEquals(1551L, replacement.fee)
        assertEquals(4859L, replacement.changeOutputValue)
    }

    @Test
    fun replacementTransaction_fractionalOriginalRate_paysStrictlyHigherRate() {
        givenOriginal(inputs = listOf(2000, 1000, 680), recipient = 1000, spareUtxo = listOf(100_000))

        val replacement = speedUp(speedUpFeeRange().first)

        assertEquals(12L * 345, replacement.fee)
        assertNewChangeOutput(replacement, value = 98_540, changeAddress)
        assertFeeRateAbove(replacement, originalFee = 2680, originalSize = 246)
    }

    @Test
    fun replacementTransaction_withDescendant_feeCoversAllReplacedFeesAndOwnSize() {
        givenQaTransaction(descendantFee = 500, spareUtxo = listOf(800))

        val floor = speedUpFeeRange().first
        val replacement = speedUp(floor)

        assertEquals(QA_FLOOR + 500, floor)
        assertEquals(QA_FEE + 500 + 345, replacement.fee)
    }

    @Test
    fun replacementTransaction_cancelAtFloor_feeCoversReplacement() {
        givenOriginal(inputs = QA_INPUTS, recipient = QA_RECIPIENT + QA_CHANGE)

        val replacement = builder.replacementTransaction(TX_HASH, cancelFeeRange().first, cancel).first

        assertEquals(QA_FEE + 249, replacement.fee)
    }

    @Test
    fun replacementTransaction_noChangeLargeSpareUtxo_returnsRestAsChange() {
        givenOriginal(inputs = QA_INPUTS, recipient = QA_RECIPIENT + QA_CHANGE, spareUtxo = listOf(100_000))

        val replacement = speedUp(speedUpFeeRange().first)

        assertEquals(4L * 345, replacement.fee)
        assertEquals(1732L, replacement.recipientOutputValue)
        assertNewChangeOutput(replacement, value = 99_451, changeAddress)
        assertEquals(102_563L, replacement.inputsToSign.sumOf { it.previousOutput.value })
        assertEquals(102_563L - 1380, replacement.outputs.sumOf { it.value })
    }

    @Test
    fun replacementTransaction_newChangeType_floorUsesItsSize() {
        val legacyAddress = LegacyAddress("legacy-change-address", ByteArray(20) { 7 }, AddressType.PubKeyHash)
        val legacyChangeBuilder = newBuilder(ScriptType.P2PKH)
        givenOriginal(inputs = QA_INPUTS, recipient = QA_RECIPIENT + QA_CHANGE, spareUtxo = listOf(100_000))
        whenever(addressConverter.convert(changeKey, ScriptType.P2PKH)).thenReturn(legacyAddress)

        val replacement = legacyChangeBuilder.replacementTransaction(TX_HASH, 1077, ReplacementType.SpeedUp).first

        assertEquals(4L * 348, replacement.fee)
        assertNewChangeOutput(replacement, value = 99_439, legacyAddress)
    }

    @Test
    fun replacementTransaction_spareUtxoRestBelowDust_keepsItInFee() {
        givenOriginal(inputs = QA_INPUTS, recipient = QA_RECIPIENT + QA_CHANGE, spareUtxo = listOf(600))

        val replacement = speedUp(speedUpFeeRange().first)

        assertEquals(1431L, replacement.fee)
        assertEquals(listOf(1732L), replacement.outputs.map { it.value })
        verify(publicKeyManager, never()).changePublicKey()
    }

    @Test
    fun replacementTransaction_spareUtxoRestEqualsNewOutputCost_addsNoZeroValueOutput() {
        givenOriginal(inputs = TEN_INPUTS, recipient = 9000, spareUtxo = listOf(821))

        val replacement = speedUp(speedUpFeeRange().first)

        assertEquals(1821L, replacement.fee)
        assertEquals(listOf(9000L), replacement.outputs.map { it.value })
        verify(publicKeyManager, never()).changePublicKey()
    }

    @Test
    fun replacementTransaction_smallChangeWithSpareUtxo_raisesExistingChange() {
        givenOriginal(inputs = listOf(875, 1003, 310), recipient = QA_RECIPIENT, change = 100, spareUtxo = listOf(100_000))

        val replacement = speedUp(speedUpFeeRange().first)

        assertEquals(4L * 345, replacement.fee)
        assertEquals(QA_RECIPIENT, replacement.recipientOutputValue)
        assertEquals(99_551L, replacement.changeOutputValue)
        assertEquals(2, replacement.outputs.size)
    }

    @Test
    fun replacementTransaction_requestedFeeAboveCandidateFloor_paysExactlyRequested() {
        givenOriginal(inputs = QA_INPUTS, recipient = QA_RECIPIENT + QA_CHANGE, spareUtxo = listOf(100_000))

        val replacement = speedUp(5000)

        assertEquals(5000L, replacement.fee)
        assertNewChangeOutput(replacement, value = 95_831, changeAddress)
    }

    @Test
    fun replacementTransaction_noSpareUtxo_doesNotRequestChangeKey() {
        givenQaTransaction()

        assertEquals(1306L, speedUp(speedUpFeeRange().first).fee)
        verify(publicKeyManager, never()).changePublicKey()
    }

    @Test
    fun replacementTransaction_existingChangeAbsorbsRest_doesNotRequestChangeKey() {
        givenOriginal(inputs = listOf(1349, 1003, 310), recipient = QA_RECIPIENT, change = 574, spareUtxo = listOf(100_000))

        val replacement = speedUp(speedUpFeeRange().first)

        assertEquals(4, replacement.inputsToSign.size)
        assertEquals(4L * 345, replacement.fee)
        assertEquals(100_025L, replacement.changeOutputValue)
        verify(publicKeyManager, never()).changePublicKey()
    }

    @Test
    fun replacementTransaction_spareUtxoWithoutSurplus_doesNotRequestChangeKey() {
        givenOriginal(inputs = TEN_INPUTS, recipient = 9000, spareUtxo = listOf(790))

        val replacement = speedUp(speedUpFeeRange().first)

        assertEquals(1790L, replacement.fee)
        assertEquals(listOf(9000L), replacement.outputs.map { it.value })
        verify(publicKeyManager, never()).changePublicKey()
    }

    @Test
    fun replacementTransaction_cheapSpareUtxoBeforeLargeOne_staysKeyFree() {
        givenOriginal(inputs = TEN_INPUTS, recipient = 8700, change = 300, spareUtxo = listOf(100_000, 500))

        val replacement = speedUp(speedUpFeeRange().first)

        assertEquals(1800L, replacement.fee)
        assertEquals(11, replacement.inputsToSign.size)
        assertEquals(listOf(8700L), replacement.outputs.map { it.value })
        verify(publicKeyManager, never()).changePublicKey()
    }

    @Test
    fun replacementTransaction_noChangeKeyAvailable_failsInsteadOfBurningUtxo() {
        givenOriginal(inputs = QA_INPUTS, recipient = QA_RECIPIENT + QA_CHANGE, spareUtxo = listOf(100_000))
        whenever(publicKeyManager.changePublicKey()).thenAnswer { throw PublicKeyManager.Error.NoUnusedPublicKey }

        assertThrows(PublicKeyManager.Error.NoUnusedPublicKey::class.java) { speedUp(1077) }
    }

    @Test
    fun replacementTransaction_cancelWithSpareUtxo_returnsRestToOwnOutput() {
        givenOriginal(inputs = listOf(312, 1003, 310), recipient = 794, spareUtxo = listOf(1000))

        val replacement = builder.replacementTransaction(TX_HASH, cancelFeeRange().first, cancel).first

        assertEquals(4L * 317, replacement.fee)
        assertEquals(listOf(1357L), replacement.outputs.map { it.value })
        assertArrayEquals(cancel.address.lockingScript, replacement.outputs.single().lockingScript)
    }

    private fun assertFeeRateAbove(replacement: MutableTransaction, originalFee: Long, originalSize: Long) {
        assertTrue(replacement.fee * originalSize > originalFee * replacement.size)
    }

    private fun assertNewChangeOutput(replacement: MutableTransaction, value: Long, address: Address) {
        val output = replacement.outputs.single { it.publicKeyPath == NEW_CHANGE_PATH }

        assertEquals(value, output.value)
        assertArrayEquals(address.lockingScript, output.lockingScript)
        assertArrayEquals(address.lockingScriptPayload, output.lockingScriptPayload)
        assertEquals(address.stringValue, output.address)
        assertEquals(address.scriptType, output.scriptType)
        assertTrue(output.changeOutput)
        verify(publicKeyManager).changePublicKey()
    }

    private fun givenQaTransaction(descendantFee: Long = 0, spareUtxo: List<Long> = emptyList()) =
        givenOriginal(QA_INPUTS, QA_RECIPIENT, QA_CHANGE, descendantFee, spareUtxo)

    private fun givenTenInputsWithCheapSpareUtxo() =
        givenOriginal(inputs = TEN_INPUTS, recipient = 8246, change = 754, spareUtxo = listOf(30))

    private fun givenOriginal(
        inputs: List<Long>,
        recipient: Long,
        change: Long? = null,
        descendantFee: Long = 0,
        spareUtxo: List<Long> = emptyList(),
        recipientPath: String? = null,
        scriptType: ScriptType = ScriptType.P2WPKH
    ) {
        val outputs = listOfNotNull(output(recipient, recipientPath, scriptType), change?.let { output(it, CHANGE_PATH, scriptType) })
        val original = fullTransactionInfo(
            inputs = inputs.map { input(output(it, INPUT_PATH, scriptType)) },
            outputs = outputs,
            fee = inputs.sum() - outputs.sumOf { it.value }
        )
        val descendants = if (descendantFee > 0) listOf(fullTransactionInfo(emptyList(), emptyList(), descendantFee)) else emptyList()

        whenever(storage.getFullTransactionInfo(any<ByteArray>())).thenReturn(original)
        whenever(storage.getDescendantTransactionsFullInfo(any())).thenReturn(listOf(original) + descendants)
        whenever(publicKeyManager.getPublicKeyByPath(any())).thenReturn(PublicKey())
        whenever(publicKeyManager.changePublicKey()).thenReturn(changeKey)
        whenever(addressConverter.convert(changeKey, ScriptType.P2WPKH)).thenReturn(changeAddress)
        whenever(unspentOutputProvider.getConfirmedSpendableUtxo(any())).thenReturn(
            spareUtxo.map { UnspentOutput(output(it, INPUT_PATH, scriptType), PublicKey(), Transaction(), null) }
        )
    }

    private fun fullTransactionInfo(inputs: List<InputWithPreviousOutput>, outputs: List<TransactionOutput>, fee: Long) =
        FullTransactionInfo(
            block = null,
            header = Transaction(2, 0),
            inputs = inputs,
            outputs = outputs,
            metadata = TransactionMetadata(ByteArray(32)).apply {
                this.fee = fee
                type = TransactionType.Outgoing
            },
            transactionSerializer = serializer
        )

    private fun input(previousOutput: TransactionOutput) =
        InputWithPreviousOutput(TransactionInput(ByteArray(32), 0, sequence = 0), previousOutput)

    private fun output(value: Long, path: String? = null, scriptType: ScriptType = ScriptType.P2WPKH) =
        TransactionOutput(value, 0, ByteArray(22), scriptType).apply {
            transactionHash = ByteArray(32)
            publicKeyPath = path
            changeOutput = path == CHANGE_PATH
        }

    private fun speedUp(minFee: Long) = builder.replacementTransaction(TX_HASH, minFee, ReplacementType.SpeedUp).first

    private fun speedUpFeeRange() = requireNotNull(builder.replacementInfo(TX_HASH, ReplacementType.SpeedUp)).feeRange

    private fun cancelFeeRange() = requireNotNull(builder.replacementInfo(TX_HASH, cancel)).feeRange

    private val MutableTransaction.fee: Long
        get() = inputsToSign.sumOf { it.previousOutput.value } - outputs.sumOf { it.value }

    private val MutableTransaction.size: Long
        get() = sizeCalculator.transactionSize(inputsToSign.map { it.previousOutput }, outputs)

    private val MutableTransaction.recipientOutputValue: Long
        get() = outputs.single { it.publicKeyPath == null }.value

    private val MutableTransaction.changeOutputValue: Long
        get() = outputs.single { it.changeOutput }.value

    private companion object {
        const val TX_HASH = "abababababababababababababababababababababababababababababababab"
        const val DUST_RELAY_TX_FEE = 3000
        const val INPUT_PATH = "0/0/0"
        const val CHANGE_PATH = "0/1/0"
        const val NEW_CHANGE_PATH = "0/1/7"

        // All-P2WPKH original: 3 inputs, 2 outputs, 277 vB.
        val QA_INPUTS = listOf(1250L, 1003L, 310L)
        const val QA_RECIPIENT = 1257L
        const val QA_CHANGE = 475L
        const val QA_FEE = 831L
        const val QA_FLOOR = QA_FEE + 277

        val TEN_INPUTS = List(10) { 1000L }
    }
}
