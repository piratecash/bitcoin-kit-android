package io.horizontalsystems.litecoinkit.mweb.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

class MwebdAndroidDaemonClientTest {
    @Test
    fun toMwebdExclusiveToIndex_inclusiveRangeEnd_convertsToExclusiveEnd() {
        listOf(
            0 to 1L,
            1 to 2L,
            100 to 101L,
        ).forEach { (inclusiveEnd, exclusiveEnd) ->
            assertEquals(exclusiveEnd, inclusiveEnd.toMwebdExclusiveToIndex())
        }
    }
}
