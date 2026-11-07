package io.horizontalsystems.bitcoincore.network.peer.task

import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class GetBlockHeadersTaskTest {

    @Test
    fun `state - task carries an idle timeout - so a peer withholding headers is dropped`() {
        val task = GetBlockHeadersTask(listOf(byteArrayOf(1)))

        assertTrue("no idle timeout set: ${task.state}", Regex("allowedIdleTime: [1-9]").containsMatchIn(task.state))
    }

    /** CheckpointSyncer reads an empty completion as "peer is synced", so a timeout must never look like one. */
    @Test
    fun `handleTimeout - peer withheld the headers - fails the task instead of reporting an empty success`() {
        val task = GetBlockHeadersTask(listOf(byteArrayOf(1)))
        val listener = mock<PeerTask.Listener>()
        task.listener = listener

        task.handleTimeout()

        verify(listener).onTaskFailed(eq(task), any())
        verify(listener, never()).onTaskCompleted(any())
    }
}
