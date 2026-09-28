package io.horizontalsystems.bitcoincore.network.transport.v2

/**
 * BIP324 short message-type identifiers (plan §2.3).
 *
 * A v2 packet encodes its message type either as one of these single bytes, or as `0x00` followed
 * by the familiar 12-byte command string. Decoding both is mandatory — Bitcoin Core always emits
 * the short form when one exists. Encoding is not: we always send the long form, which is valid per
 * the BIP and removes any need for a reverse table.
 *
 * Two namespaces:
 *  - 1..28 — assigned by BIP324 itself, used by every v2 network.
 *  - 128..168 — Dash's reserved upper half, used by Dash, PirateCash and Cosanta only.
 *
 * The Dash half is not optional for those chains. `v22.1.4-pirate` — the first release shipping
 * `DEFAULT_V2_TRANSPORT{true}` — emits these unconditionally (`if (short_message_id)`, with no
 * protocol-version gate; that gate only appeared in `v23.1.7-pirate`). Without the table,
 * `mnlistdiff`, `getmnlistd` and `isdlock` would arrive as unknown messages and masternode and
 * InstantSend synchronization would silently stop working.
 *
 * ID 37 (`feature`) is a separate BIP434 allocation and is deliberately absent.
 */
internal object V2ShortIds {

    private val BITCOIN_IDS: Map<Int, String> = mapOf(
        1 to "addr",
        2 to "block",
        3 to "blocktxn",
        4 to "cmpctblock",
        5 to "feefilter",
        6 to "filteradd",
        7 to "filterclear",
        8 to "filterload",
        9 to "getblocks",
        10 to "getblocktxn",
        11 to "getdata",
        12 to "getheaders",
        13 to "headers",
        14 to "inv",
        15 to "mempool",
        16 to "merkleblock",
        17 to "notfound",
        18 to "ping",
        19 to "pong",
        20 to "sendcmpct",
        21 to "tx",
        22 to "getcfilters",
        23 to "cfilter",
        24 to "getcfheaders",
        25 to "cfheaders",
        26 to "getcfcheckpt",
        27 to "cfcheckpt",
        28 to "addrv2",
    )

    /**
     * Slots are append-only by Dash Core's own rule ("slots should not be reused"), which is what
     * makes pinning them in a test meaningful: an inserted entry would shift every later ID and
     * silently misroute messages rather than fail loudly.
     */
    private val DASH_IDS: Map<Int, String> = mapOf(
        128 to "spork",
        129 to "getsporks",
        130 to "senddsq",
        131 to "dsa",
        132 to "dsi",
        133 to "dsf",
        134 to "dss",
        135 to "dsc",
        136 to "dssu",
        137 to "dstx",
        138 to "dsq",
        139 to "ssc",
        140 to "govsync",
        141 to "govobj",
        142 to "govobjvote",
        143 to "getmnlistd",
        144 to "mnlistdiff",
        145 to "qsendrecsigs",
        146 to "qfcommit",
        147 to "qcontrib",
        148 to "qcomplaint",
        149 to "qjustify",
        150 to "qpcommit",
        151 to "qwatch",
        152 to "qsigsesann",
        153 to "qsigsinv",
        154 to "qgetsigs",
        155 to "qbsigs",
        156 to "qsigrec",
        157 to "qsigshare",
        158 to "qgetdata",
        159 to "qdata",
        160 to "clsig",
        161 to "isdlock",
        162 to "mnauth",
        163 to "getheaders2",
        164 to "sendheaders2",
        165 to "headers2",
        166 to "getqrinfo",
        167 to "qrinfo",
        168 to "platformban",
    )

    /** Returns the command for [id], or null when the id is unassigned in the applicable namespaces. */
    fun command(id: Int, includeDashNamespace: Boolean): String? =
        BITCOIN_IDS[id] ?: if (includeDashNamespace) DASH_IDS[id] else null

    /** Test seam: the full table, so an accidental insertion fails loudly instead of misrouting. */
    internal fun table(includeDashNamespace: Boolean): Map<Int, String> =
        if (includeDashNamespace) BITCOIN_IDS + DASH_IDS else BITCOIN_IDS
}
