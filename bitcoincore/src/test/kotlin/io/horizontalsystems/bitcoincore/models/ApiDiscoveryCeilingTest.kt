package io.horizontalsystems.bitcoincore.models

import io.horizontalsystems.bitcoincore.core.IStorage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ApiDiscoveryCeilingTest {

    private val storage = mock<IStorage>()
    private val checkpoint = mock<Checkpoint>()

    @Test
    fun `apiDiscoveryCeiling - a stored tip exists - the scan reaches up to it`() {
        whenever(checkpoint.block).thenReturn(block(CHECKPOINT_HEIGHT))
        whenever(storage.lastBlock()).thenReturn(block(TIP_HEIGHT))

        assertEquals(TIP_HEIGHT, Checkpoint.apiDiscoveryCeiling(checkpoint, storage))
    }

    @Test
    fun `apiDiscoveryCeiling - empty database - the scan stops at the checkpoint`() {
        whenever(checkpoint.block).thenReturn(block(CHECKPOINT_HEIGHT))
        whenever(storage.lastBlock()).thenReturn(null)

        assertEquals(CHECKPOINT_HEIGHT, Checkpoint.apiDiscoveryCeiling(checkpoint, storage))
    }

    private fun block(height: Int) = Block().apply { this.height = height }

    private companion object {
        const val CHECKPOINT_HEIGHT = 3_173_184
        const val TIP_HEIGHT = 3_200_000
    }
}
