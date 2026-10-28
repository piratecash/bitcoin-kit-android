package io.horizontalsystems.bitcoincore.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.SentTransaction
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

// Storage-level coverage for the stuck-change-expiry feature (MOBILE-748):
//  - deleting a never-broadcast NEW transaction frees its funding UTXO without marking it
//    failedToSpend (Phase 2), and only when it is still NEW at delete time (Phase 1)
// Coordination with a concurrent broadcast (so the delete never races a real send) is handled
// structurally: TransactionSender always writes a SentTransaction record before a transaction's
// bytes reach the network, and deleteNewExpiredTransactions atomically skips any transaction that
// has one. recordBroadcastAttemptIfPending is the other half of that guard: it re-checks the
// transaction is still pending and inserts the SentTransaction inside the same DB transaction, so
// it and a concurrent expiry delete can never both succeed for the same transaction.
@RunWith(RobolectricTestRunner::class)
class PendingTransactionExpiryStorageTest {

    private lateinit var database: CoreDatabase
    private lateinit var storage: Storage

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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deleteNewExpiredTransactions_newSpenderStillNew_freesFundingOutputWithoutFailedToSpend() {
        val fundingTransaction = fundingTransaction(hashByte = 1)
        insertFundingTransaction(fundingTransaction)
        val spenderTransaction = spenderTransaction(hashByte = 2, status = Transaction.Status.NEW)
        insertSpenderTransaction(spenderTransaction, fundingTransaction)

        // Before the delete, the spender's input must actually lock the funding output out of the
        // unspent set - otherwise the "delete frees it" assertion below would pass trivially.
        val lockedBeforeDelete = storage.getUnspentOutputs().none { it.transaction.hash.contentEquals(fundingTransaction.hash) }
        assertTrue("Funding output must be locked by the spender's input before delete", lockedBeforeDelete)

        val deleted = storage.deleteNewExpiredTransactions(listOf(spenderTransaction))

        assertEquals(listOf(spenderTransaction.hash.toList()), deleted.map { it.hash.toList() })
        assertNull(storage.getTransaction(spenderTransaction.hash))
        val unspent = storage.getUnspentOutputs().singleOrNull { it.transaction.hash.contentEquals(fundingTransaction.hash) }
        assertNotNull("Funding output should be spendable again", unspent)
        assertFalse("Funding output must not be marked failedToSpend", unspent!!.output.failedToSpend)
    }

    @Test
    fun deleteNewExpiredTransactions_spenderAlreadyRelayed_skipsDeletion() {
        val fundingTransaction = fundingTransaction(hashByte = 1)
        insertFundingTransaction(fundingTransaction)
        val spenderTransaction = spenderTransaction(hashByte = 2, status = Transaction.Status.RELAYED)
        insertSpenderTransaction(spenderTransaction, fundingTransaction)

        val deleted = storage.deleteNewExpiredTransactions(listOf(spenderTransaction))

        assertTrue("A RELAYED transaction must not be deleted via the NEW-expiry path", deleted.isEmpty())
        assertNotNull(storage.getTransaction(spenderTransaction.hash))
    }

    @Test
    fun deleteNewExpiredTransactions_spenderHasSentTransaction_skipsDeletion() {
        val fundingTransaction = fundingTransaction(hashByte = 1)
        insertFundingTransaction(fundingTransaction)
        val spenderTransaction = spenderTransaction(hashByte = 2, status = Transaction.Status.NEW)
        insertSpenderTransaction(spenderTransaction, fundingTransaction)
        // A SentTransaction record means the transaction's bytes are being (or were) handed to the
        // network - see TransactionSender.recordBroadcastAttempt - so the expiry delete must not run.
        database.sentTransaction.insert(SentTransaction(spenderTransaction.hash))

        val deleted = storage.deleteNewExpiredTransactions(listOf(spenderTransaction))

        assertTrue("A transaction with a SentTransaction record must not be expiry-deleted", deleted.isEmpty())
        assertNotNull(storage.getTransaction(spenderTransaction.hash))
    }

    @Test
    fun deleteNewExpiredTransactions_spenderRelayedWithSentTransactionRowStillPresent_skipsDeletion() {
        // Models the post-accept window produced by TransactionSender.markBroadcastAccepted: for an
        // own transaction, handleRelayed (status -> RELAYED) now runs before the SentTransaction guard
        // row is removed, so this transient state (RELAYED, row still present) must be just as safe as
        // the final state (RELAYED, row gone) - status alone must already block the delete.
        val fundingTransaction = fundingTransaction(hashByte = 1)
        insertFundingTransaction(fundingTransaction)
        val spenderTransaction = spenderTransaction(hashByte = 2, status = Transaction.Status.NEW)
        insertSpenderTransaction(spenderTransaction, fundingTransaction)
        database.sentTransaction.insert(SentTransaction(spenderTransaction.hash))

        database.transaction.insert(spenderTransaction.apply { status = Transaction.Status.RELAYED })

        val deleted = storage.deleteNewExpiredTransactions(listOf(spenderTransaction))

        assertTrue("An accepted (RELAYED) transaction must never be expiry-deleted, guard row or not", deleted.isEmpty())
        assertNotNull(storage.getTransaction(spenderTransaction.hash))
    }

    @Test
    fun recordBroadcastAttemptIfPending_spenderStillNew_insertsSentTransactionSoSubsequentExpiryDeleteSkipsIt() {
        val fundingTransaction = fundingTransaction(hashByte = 1)
        insertFundingTransaction(fundingTransaction)
        val spenderTransaction = spenderTransaction(hashByte = 2, status = Transaction.Status.NEW)
        insertSpenderTransaction(spenderTransaction, fundingTransaction)

        val recorded = storage.recordBroadcastAttemptIfPending(SentTransaction(spenderTransaction.hash))

        assertTrue("A still-pending NEW transaction must record a broadcast attempt", recorded)
        val deleted = storage.deleteNewExpiredTransactions(listOf(spenderTransaction))
        assertTrue("A transaction with a freshly recorded SentTransaction must not be expiry-deleted", deleted.isEmpty())
        assertNotNull(storage.getTransaction(spenderTransaction.hash))
    }

    @Test
    fun recordBroadcastAttemptIfPending_spenderAlreadyRelayed_returnsFalseWithoutWriting() {
        val fundingTransaction = fundingTransaction(hashByte = 1)
        insertFundingTransaction(fundingTransaction)
        val spenderTransaction = spenderTransaction(hashByte = 2, status = Transaction.Status.RELAYED)
        insertSpenderTransaction(spenderTransaction, fundingTransaction)

        val recorded = storage.recordBroadcastAttemptIfPending(SentTransaction(spenderTransaction.hash))

        assertFalse("A mined transaction must not record a broadcast attempt", recorded)
        assertNull(database.sentTransaction.getTransaction(spenderTransaction.hash))
    }

    @Test
    fun recordBroadcastAttemptIfPending_spenderDeletedByConcurrentExpiry_returnsFalseWithoutOrphaningSentTransaction() {
        val fundingTransaction = fundingTransaction(hashByte = 1)
        insertFundingTransaction(fundingTransaction)
        val spenderTransaction = spenderTransaction(hashByte = 2, status = Transaction.Status.NEW)
        insertSpenderTransaction(spenderTransaction, fundingTransaction)
        storage.deleteNewExpiredTransactions(listOf(spenderTransaction))

        val recorded = storage.recordBroadcastAttemptIfPending(SentTransaction(spenderTransaction.hash))

        assertFalse("A concurrently expiry-deleted transaction must not record a broadcast attempt", recorded)
        assertNull(
            "No orphan SentTransaction row may be written for a deleted transaction",
            database.sentTransaction.getTransaction(spenderTransaction.hash)
        )
    }

    private fun insertFundingTransaction(transaction: Transaction) {
        database.transaction.insert(transaction)
        database.publicKey.insertOrIgnore(listOf(publicKey(transaction.hash)))
        database.output.insert(fundingOutput(transaction.hash))
    }

    private fun insertSpenderTransaction(transaction: Transaction, fundingTransaction: Transaction) {
        database.transaction.insert(transaction)
        database.input.insert(input(spenderHash = transaction.hash, fundingHash = fundingTransaction.hash))
    }

    private fun spenderTransaction(hashByte: Byte, status: Int): Transaction {
        return Transaction().apply {
            hash = byteArrayOf(hashByte)
            timestamp = 100
            isMine = true
            isOutgoing = true
            this.status = status
        }
    }

    private fun fundingTransaction(hashByte: Byte): Transaction {
        return Transaction().apply {
            hash = byteArrayOf(hashByte)
            timestamp = 50
            isMine = true
            isOutgoing = false
            status = Transaction.Status.RELAYED
        }
    }

    // Uses the no-arg constructor and sets fields directly instead of the (account, index, external,
    // publicKey, publicKeyHash) constructor, which derives a taproot key from the public key bytes and
    // requires a real point on the secp256k1 curve - unnecessary for these storage-layer tests.
    private fun publicKey(seed: ByteArray): PublicKey {
        return PublicKey().apply {
            path = seed.toHexString()
            account = 0
            index = 0
            external = true
            publicKey = VALID_PUBLIC_KEY_HEX.hexToByteArray()
            publicKeyHash = VALID_PUBLIC_KEY_HEX.hexToByteArray()
        }
    }

    private fun fundingOutput(transactionHash: ByteArray): TransactionOutput {
        return TransactionOutput().apply {
            this.transactionHash = transactionHash
            index = 0
            value = 100_000
            scriptType = ScriptType.P2PKH
            publicKeyPath = publicKey(transactionHash).path
        }
    }

    private fun input(spenderHash: ByteArray, fundingHash: ByteArray): TransactionInput {
        return TransactionInput(
            previousOutputTxHash = fundingHash,
            previousOutputIndex = 0,
            sequence = 0
        ).apply {
            transactionHash = spenderHash
        }
    }

    private companion object {
        const val VALID_PUBLIC_KEY_HEX = "037d56797fbe9aa506fc263751abf23bb46c9770181a6059096808923f0a64cb15"
    }
}
