package io.horizontalsystems.bitcoincore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.bitcoincore.models.Block
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class CoreDatabaseConcurrentInstancesAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = ByteArray(32) { it.toByte() }
    private val name = "core-${UUID.randomUUID()}.db"
    private val dataDir = requireNotNull(context.getDatabasePath(name).parentFile).also { it.mkdirs() }.path

    @After
    fun tearDown() {
        // Closing is safe only here: deleteDatabaseFiles evicts it, so the cache never hands it out again.
        open().close()
        deleteDatabaseFiles(dataDir, name)
    }

    @Test
    fun getInstance_samePathAndKey_returnsSameInstance() {
        assertSame(open(), open())
    }

    @Test
    fun deleteBlocks_secondCallerWhileWriterHoldsTransaction_waitsAndDeletes() {
        val first = open()
        val second = open()
        val partial = block(1).also { first.block.insert(it) }

        val waitedMs = holdingWriteTransaction(first, maxHoldSeconds = 4) {
            val start = System.nanoTime()
            Storage(second).deleteBlocks(listOf(partial))
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        }

        assertTrue("deleteBlocks did not wait for the writer: ${waitedMs}ms", waitedMs >= 3_000)
        assertNull(second.block.getBlockByHash(partial.headerHash))
    }

    private fun open(): CoreDatabase = CoreDatabase.getInstance(dataDir, name, key)

    // Keeps a write transaction open on [holder] for up to [maxHoldSeconds] while [body] runs on this thread.
    private fun <T> holdingWriteTransaction(holder: CoreDatabase, maxHoldSeconds: Long, body: () -> T): T {
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = thread {
            holder.runInTransaction {
                holder.block.insert(block(2))
                holding.countDown()
                release.await(maxHoldSeconds, TimeUnit.SECONDS)
            }
        }
        check(holding.await(10, TimeUnit.SECONDS)) { "writer never started its transaction" }
        try {
            return body()
        } finally {
            release.countDown()
            writer.join()
        }
    }

    private fun block(height: Int) = Block().apply {
        headerHash = ByteArray(32) { height.toByte() }
        this.height = height
        partial = true
    }
}
