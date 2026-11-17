package io.horizontalsystems.bitcoincore.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.InvalidTransaction
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.SentTransaction
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class StorageReplacementTest {
    private lateinit var database: CoreDatabase
    private lateinit var storage: Storage

    private val funding = byteArrayOf(1)
    private val spare = byteArrayOf(2)
    private val original = byteArrayOf(3)
    private val first = byteArrayOf(4)
    private val second = byteArrayOf(5)

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CoreDatabase::class.java
        )
            .setTransactionExecutor(Executors.newSingleThreadExecutor())
            .setQueryExecutor(Executors.newSingleThreadExecutor())
            .allowMainThreadQueries()
            .build()

        storage = Storage(database)
        insertWithOutput(funding, Transaction.Status.RELAYED, blockHash = MINED_BLOCK)
        insertWithOutput(spare, Transaction.Status.RELAYED, blockHash = MINED_BLOCK)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getUnspentOutputs_replacementNotRelayed_countsOnlyReplacement() {
        insertSpender(original, Transaction.Status.RELAYED, sequence = 0)
        insertSpender(first, Transaction.Status.NEW, sequence = 1)

        assertUnspent(first)
    }

    @Test
    fun getUnspentOutputs_originalMinedReplacementPending_countsOnlyOriginal() {
        insertSpender(original, Transaction.Status.RELAYED, sequence = 0, blockHash = MINED_BLOCK)
        insertSpender(first, Transaction.Status.NEW, sequence = 1)

        assertUnspent(original)
    }

    @Test
    fun getUnspentOutputs_chainOfThree_countsOnlyNewest() {
        insertSpender(original, Transaction.Status.RELAYED, sequence = 0)
        insertSpender(first, Transaction.Status.RELAYED, sequence = 1)
        insertSpender(second, Transaction.Status.NEW, sequence = 2)

        assertUnspent(second)
    }

    @Test
    fun getUnspentOutputs_loserInvalidated_originalCountedAgain() {
        insertSpender(original, Transaction.Status.RELAYED, sequence = 0)
        insertSpender(first, Transaction.Status.NEW, sequence = 1)
        assertUnspent(first)

        invalidate(first)

        assertUnspent(original)
    }

    @Test
    fun moveTransactionToInvalidTransactions_lostConflict_spareOutputSpendableAndSendRowRemoved() {
        insertSpender(original, Transaction.Status.RELAYED, sequence = 0, blockHash = MINED_BLOCK)
        insertSpender(first, Transaction.Status.NEW, sequence = 1, extraFunding = spare)
        database.sentTransaction.insert(SentTransaction(first))

        invalidate(first)

        val spareOutput = storage.getUnspentOutputs().single { it.transaction.hash.contentEquals(spare) }
        assertFalse(spareOutput.output.failedToSpend)
        assertNull(database.sentTransaction.getTransaction(first))
    }

    @Test
    fun moveTransactionToInvalidTransactions_noCompetitor_outputMarked() {
        insertSpender(original, Transaction.Status.RELAYED, sequence = 0)

        invalidate(original)

        val fundingOutput = storage.getUnspentOutputs().single { it.transaction.hash.contentEquals(funding) }
        assertEquals(true, fundingOutput.output.failedToSpend)
    }

    @Test
    fun moveTransactionToInvalidTransactions_conflictSetNoSharedInput_outputNotMarked() {
        insertSpender(original, Transaction.Status.RELAYED, sequence = 0, conflictingTxHash = first)

        invalidate(original)

        val fundingOutput = storage.getUnspentOutputs().single { it.transaction.hash.contentEquals(funding) }
        assertFalse(fundingOutput.output.failedToSpend)
    }

    @Test
    fun moveTransactionToInvalidTransactions_winnerRemoved_originalCountedAgain() {
        insertSpender(original, Transaction.Status.RELAYED, sequence = 0, conflictingTxHash = first)
        insertSpender(first, Transaction.Status.RELAYED, sequence = 1)
        assertUnspent(first)

        invalidate(first)

        assertUnspent(original)
    }

    @Test
    fun moveTransactionToInvalidTransactions_removedHasWinner_linkMovesToItsWinner() {
        insertSpender(original, Transaction.Status.RELAYED, sequence = 0, conflictingTxHash = first)
        insertSpender(first, Transaction.Status.RELAYED, sequence = 1, conflictingTxHash = second)

        invalidate(first)

        assertArrayEquals(second, storage.getTransaction(original)?.conflictingTxHash)
    }

    @Test
    fun deleteNewExpiredTransactions_removedHasWinner_linkMovesToItsWinner() {
        insertSpender(original, Transaction.Status.RELAYED, sequence = 0, conflictingTxHash = first)
        insertSpender(first, Transaction.Status.NEW, sequence = 1, conflictingTxHash = second)

        storage.deleteNewExpiredTransactions(listOf(requireNotNull(storage.getTransaction(first))))

        assertArrayEquals(second, storage.getTransaction(original)?.conflictingTxHash)
    }

    @Test
    fun deleteNewExpiredTransactions_winnerRemoved_originalCountedAgain() {
        insertSpender(original, Transaction.Status.RELAYED, sequence = 0, conflictingTxHash = first)
        insertSpender(first, Transaction.Status.NEW, sequence = 1)

        storage.deleteNewExpiredTransactions(listOf(requireNotNull(storage.getTransaction(first))))

        assertUnspent(original)
    }

    @Test
    fun moveConflicts_loserHadBeatenOriginal_originalPointsAtWinner() {
        insertSpender(original, Transaction.Status.RELAYED, sequence = 0, conflictingTxHash = first)
        insertSpender(first, Transaction.Status.RELAYED, sequence = 1)

        storage.moveConflicts(loser = first, winner = second)

        assertArrayEquals(second, storage.getTransaction(original)?.conflictingTxHash)
    }

    @Test
    fun moveConflicts_winnerWasBeatenByLoser_winnerKeepsItsLink() {
        insertSpender(original, Transaction.Status.RELAYED, sequence = 0, conflictingTxHash = first)
        insertSpender(first, Transaction.Status.RELAYED, sequence = 1)

        storage.moveConflicts(loser = first, winner = original)

        assertArrayEquals(first, storage.getTransaction(original)?.conflictingTxHash)
    }

    private fun assertUnspent(vararg expected: ByteArray) {
        val unspent = storage.getUnspentOutputs().map { it.transaction.hash.toHexString() }.toSet() -
            setOf(spare.toHexString())
        assertEquals(expected.map { it.toHexString() }.toSet(), unspent)
    }

    private fun invalidate(hash: ByteArray) {
        val transaction = requireNotNull(storage.getTransaction(hash))
        storage.moveTransactionToInvalidTransactions(listOf(InvalidTransaction(transaction, "", null)))
    }

    private fun insertSpender(
        hash: ByteArray,
        status: Int,
        sequence: Long,
        blockHash: ByteArray? = null,
        conflictingTxHash: ByteArray? = null,
        extraFunding: ByteArray? = null,
    ) {
        insertWithOutput(hash, status, blockHash, conflictingTxHash)
        database.input.insert(input(hash, funding, sequence))
        extraFunding?.let { database.input.insert(input(hash, it, sequence)) }
    }

    private fun insertWithOutput(
        hash: ByteArray,
        status: Int,
        blockHash: ByteArray? = null,
        conflictingTxHash: ByteArray? = null,
    ) {
        blockHash?.let(::insertBlock)
        database.transaction.insert(
            Transaction().apply {
                this.hash = hash
                this.status = status
                this.blockHash = blockHash
                this.conflictingTxHash = conflictingTxHash
                timestamp = 100
                isMine = true
                isOutgoing = true
            }
        )
        database.publicKey.insertOrIgnore(listOf(publicKey(hash)))
        database.output.insert(
            TransactionOutput().apply {
                transactionHash = hash
                index = 0
                value = 1_000
                scriptType = ScriptType.P2PKH
                publicKeyPath = hash.toHexString()
            }
        )
    }

    private fun insertBlock(hash: ByteArray) {
        if (database.block.getBlockByHash(hash) != null) return
        val header = BlockHeader(1, byteArrayOf(), byteArrayOf(), 1, 1, 1, hash)
        database.block.insert(Block(header, 1))
    }

    // No-arg constructor: the full one derives a taproot key and needs a real curve point.
    private fun publicKey(seed: ByteArray) = PublicKey().apply {
        path = seed.toHexString()
        account = 0
        index = 0
        external = true
        publicKey = VALID_PUBLIC_KEY_HEX.hexToByteArray()
        publicKeyHash = VALID_PUBLIC_KEY_HEX.hexToByteArray()
    }

    private fun input(spender: ByteArray, spent: ByteArray, sequence: Long) = TransactionInput(
        previousOutputTxHash = spent,
        previousOutputIndex = 0,
        sequence = sequence
    ).apply {
        transactionHash = spender
    }

    private companion object {
        val MINED_BLOCK = byteArrayOf(9)
        const val VALID_PUBLIC_KEY_HEX = "037d56797fbe9aa506fc263751abf23bb46c9770181a6059096808923f0a64cb15"
    }
}
