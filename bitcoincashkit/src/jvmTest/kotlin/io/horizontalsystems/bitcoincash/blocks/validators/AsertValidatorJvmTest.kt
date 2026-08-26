package io.horizontalsystems.bitcoincash.blocks.validators

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.models.Block
import org.junit.Assert.assertThrows
import org.junit.Test

// The ASERT anchor: block 661647, whose parent carries this timestamp.
private const val ANCHOR_HEIGHT = 661647
private const val ANCHOR_PARENT_TIME = 1605447844L

// What the anchor's 0x1804dafe decays to once a block arrives exactly on schedule.
private const val ON_SCHEDULE_BITS = 0x1804d806L

private fun block(height: Int, timestamp: Long, bits: Long = 0) = Block().apply {
    this.height = height
    this.timestamp = timestamp
    this.bits = bits
}

class AsertValidatorJvmTest {

    private val validator = AsertValidator()

    @Test
    fun validate_previousBlockOnSchedule_doesNotThrow() {
        listOf(
            block(ANCHOR_HEIGHT, ANCHOR_PARENT_TIME),
            block(ANCHOR_HEIGHT + 1, ANCHOR_PARENT_TIME + 600),
            block(ANCHOR_HEIGHT + 144, ANCHOR_PARENT_TIME + 86_400),
        ).forEach { previousBlock ->
            validator.validate(block(previousBlock.height + 1, previousBlock.timestamp, ON_SCHEDULE_BITS), previousBlock)
        }
    }

    @Test
    fun validate_bitsDifferFromAsertTarget_throwsNotEqualBits() {
        val previousBlock = block(ANCHOR_HEIGHT, ANCHOR_PARENT_TIME)

        assertThrows(BlockValidatorException.NotEqualBits::class.java) {
            validator.validate(block(ANCHOR_HEIGHT + 1, ANCHOR_PARENT_TIME, ON_SCHEDULE_BITS + 1), previousBlock)
        }
    }
}
