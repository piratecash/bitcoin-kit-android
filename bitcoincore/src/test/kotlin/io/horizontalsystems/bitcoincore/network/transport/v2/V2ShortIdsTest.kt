package io.horizontalsystems.bitcoincore.network.transport.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the short-ID tables byte for byte.
 *
 * Both namespaces are append-only by their respective projects' rules, which is exactly why this
 * test matters: an entry inserted rather than appended shifts every later id by one and silently
 * misroutes messages — `mnlistdiff` arriving as `getmnlistd` and so on — instead of failing.
 */
class V2ShortIdsTest {

    @Test
    fun bitcoinNamespace_matchesBip324Assignments() {
        val expected = mapOf(
            1 to "addr", 2 to "block", 3 to "blocktxn", 4 to "cmpctblock", 5 to "feefilter",
            6 to "filteradd", 7 to "filterclear", 8 to "filterload", 9 to "getblocks",
            10 to "getblocktxn", 11 to "getdata", 12 to "getheaders", 13 to "headers", 14 to "inv",
            15 to "mempool", 16 to "merkleblock", 17 to "notfound", 18 to "ping", 19 to "pong",
            20 to "sendcmpct", 21 to "tx", 22 to "getcfilters", 23 to "cfilter",
            24 to "getcfheaders", 25 to "cfheaders", 26 to "getcfcheckpt", 27 to "cfcheckpt",
            28 to "addrv2",
        )

        assertEquals(expected, V2ShortIds.table(includeDashNamespace = false))
    }

    @Test
    fun dashNamespace_matchesDashCoreAssignments() {
        val expected = mapOf(
            128 to "spork", 129 to "getsporks", 130 to "senddsq", 131 to "dsa", 132 to "dsi",
            133 to "dsf", 134 to "dss", 135 to "dsc", 136 to "dssu", 137 to "dstx", 138 to "dsq",
            139 to "ssc", 140 to "govsync", 141 to "govobj", 142 to "govobjvote",
            143 to "getmnlistd", 144 to "mnlistdiff", 145 to "qsendrecsigs", 146 to "qfcommit",
            147 to "qcontrib", 148 to "qcomplaint", 149 to "qjustify", 150 to "qpcommit",
            151 to "qwatch", 152 to "qsigsesann", 153 to "qsigsinv", 154 to "qgetsigs",
            155 to "qbsigs", 156 to "qsigrec", 157 to "qsigshare", 158 to "qgetdata",
            159 to "qdata", 160 to "clsig", 161 to "isdlock", 162 to "mnauth",
            163 to "getheaders2", 164 to "sendheaders2", 165 to "headers2", 166 to "getqrinfo",
            167 to "qrinfo", 168 to "platformban",
        )

        val dashOnly = V2ShortIds.table(includeDashNamespace = true)
            .filterKeys { it >= 128 }

        assertEquals(expected, dashOnly)
    }

    /**
     * The three Dash-family messages this library actually parses. Without them a PirateCash node
     * running v22.1.4 — which emits short ids with no version gate — would leave masternode and
     * InstantSend synchronization silently broken.
     */
    @Test
    fun dashNamespace_coversTheMessagesTheKitsParse() {
        assertEquals("getmnlistd", V2ShortIds.command(143, includeDashNamespace = true))
        assertEquals("mnlistdiff", V2ShortIds.command(144, includeDashNamespace = true))
        assertEquals("isdlock", V2ShortIds.command(161, includeDashNamespace = true))
    }

    @Test
    fun dashNamespace_isInvisibleToBitcoinOnlyNetworks() {
        assertNull(V2ShortIds.command(144, includeDashNamespace = false))
        assertEquals("tx", V2ShortIds.command(21, includeDashNamespace = false))
    }

    @Test
    fun unassignedIds_returnNull() {
        // 29..32 are reserved-but-unassigned in BIP324, 37 is a separate BIP434 allocation that
        // this implementation deliberately does not claim, and 169+ is past Dash's used range.
        listOf(0, 29, 32, 37, 127, 169, 255).forEach {
            assertNull("id $it must be unmapped", V2ShortIds.command(it, includeDashNamespace = true))
        }
    }
}
