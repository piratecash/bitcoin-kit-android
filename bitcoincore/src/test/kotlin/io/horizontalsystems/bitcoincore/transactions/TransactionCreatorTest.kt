package io.horizontalsystems.bitcoincore.transactions

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doNothing
import com.nhaarman.mockitokotlin2.doThrow
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.network.peer.PeerGroup
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.transactions.builder.MutableTransaction
import io.horizontalsystems.bitcoincore.transactions.builder.TransactionBuilder
import io.horizontalsystems.bitcoincore.transactions.builder.TransactionSigner
import kotlinx.coroutines.runBlocking
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe

object TransactionCreatorTest : Spek({

    val builder by memoized { mock<TransactionBuilder> {} }
    val processor by memoized { mock<PendingTransactionProcessor> {} }
    val transactionSender by memoized { mock<TransactionSender> {} }
    val transactionSigner by memoized { mock<TransactionSigner> {} }
    val bloomFilterManager by memoized { mock<BloomFilterManager> {} }
    val transactionSerializer by memoized { mock<BaseTransactionSerializer> {} }

    val transactionCreator by memoized {
        TransactionCreator(
            builder,
            processor,
            transactionSender,
            transactionSigner,
            bloomFilterManager,
            transactionSerializer
        )
    }

    val mockTransaction by memoized { mock<FullTransaction> {} }

    val mutableTransaction by memoized {
        mock<MutableTransaction> {
            on { build(transactionSerializer) }.thenReturn(mockTransaction)
        }
    }

    describe("#create(MutableTransaction)") {

        context("when peers are available and broadcast succeeds") {
            beforeEachTest {
                doNothing().whenever(processor).processCreated(any())
                doNothing().whenever(transactionSender).sendPendingTransactions()
            }

            it("processes transaction and attempts broadcast") {
                runBlocking {
                    transactionCreator.create(mutableTransaction)

                    verify(transactionSigner).sign(mutableTransaction)
                }
                verify(processor).processCreated(mockTransaction)
                verify(transactionSender).sendPendingTransactions()
            }
        }

        context("when transactionSender throws PeerGroup.Error") {
            beforeEachTest {
                doNothing().whenever(processor).processCreated(any())
                doThrow(PeerGroup.Error("Peers not synced: connected=0, synced=0, ready=0"))
                    .whenever(transactionSender).sendPendingTransactions()
            }

            it("still saves the transaction to database") {
                runBlocking {
                    transactionCreator.create(mutableTransaction)
                }

                verify(processor).processCreated(mockTransaction)
            }

            it("does not propagate the exception") {
                runBlocking {
                    // Should not throw - exception is caught internally
                    transactionCreator.create(mutableTransaction)
                }
            }
        }

        context("when transactionSender throws generic Exception") {
            beforeEachTest {
                doNothing().whenever(processor).processCreated(any())
                doThrow(RuntimeException("Network error"))
                    .whenever(transactionSender).sendPendingTransactions()
            }

            it("still saves the transaction to database") {
                runBlocking {
                    transactionCreator.create(mutableTransaction)
                }

                verify(processor).processCreated(mockTransaction)
            }

            it("does not propagate the exception") {
                runBlocking {
                    // Should not throw - exception is caught internally
                    transactionCreator.create(mutableTransaction)
                }
            }
        }

        context("when processor throws BloomFilterExpired") {
            beforeEachTest {
                doThrow(BloomFilterManager.BloomFilterExpired)
                    .whenever(processor).processCreated(any())
                doNothing().whenever(transactionSender).sendPendingTransactions()
            }

            it("regenerates bloom filter") {
                runBlocking {
                    transactionCreator.create(mutableTransaction)
                }

                verify(bloomFilterManager).regenerateBloomFilter()
            }

            it("still attempts to broadcast") {
                runBlocking {
                    transactionCreator.create(mutableTransaction)
                }

                verify(transactionSender).sendPendingTransactions()
            }
        }

        context("when processor throws BloomFilterExpired AND sender throws PeerGroup.Error") {
            beforeEachTest {
                doThrow(BloomFilterManager.BloomFilterExpired)
                    .whenever(processor).processCreated(any())
                doThrow(PeerGroup.Error("Peers not synced"))
                    .whenever(transactionSender).sendPendingTransactions()
            }

            it("handles both exceptions gracefully") {
                runBlocking {
                    // Should not throw
                    transactionCreator.create(mutableTransaction)
                }

                verify(bloomFilterManager).regenerateBloomFilter()
            }
        }

        context("when processor throws unexpected exception") {
            beforeEachTest {
                doThrow(RuntimeException("Database error"))
                    .whenever(processor).processCreated(any())
            }

            it("does not attempt to broadcast") {
                try {
                    runBlocking {
                        transactionCreator.create(mutableTransaction)
                    }
                } catch (e: RuntimeException) {
                    // Expected
                }

                verify(transactionSender, never()).sendPendingTransactions()
            }
        }
    }
})
