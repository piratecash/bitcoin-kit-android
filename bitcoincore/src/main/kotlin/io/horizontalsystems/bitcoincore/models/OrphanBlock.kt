package io.horizontalsystems.bitcoincore.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.storage.BlockHeader

/**
 * OrphanBlock
 *
 *  Size        Field           Description
 *  ====        =====           ===========
 *  80 bytes    Header          Consists of 6 fields that are hashed to calculate the block hash
 *  VarInt      TxCount         Number of transactions in the block
 *  Variable    Transactions    The transactions in the block
 */

@Entity
class OrphanBlock() {

    //  Header
    @ColumnInfo(name = "block_version")
    var version: Int = 0
    var previousBlockHash: ByteArray = byteArrayOf()
    var merkleRoot: ByteArray = byteArrayOf()
    @ColumnInfo(name = "block_timestamp")
    var timestamp: Long = 0
    var bits: Long = 0
    var nonce: Long = 0
    var hasTransactions = false

    @PrimaryKey
    var headerHash: ByteArray = byteArrayOf()

    @Ignore
    var merkleBlock: MerkleBlock? = null

    fun previousBlock(storage: IStorage): OrphanBlock? {
        return storage.getOrphanBlock(hashHash = previousBlockHash)
    }

    constructor(merkleBlock: MerkleBlock) : this(merkleBlock.header, merkleBlock)
    constructor(header: BlockHeader, merkleBlock: MerkleBlock? = null) : this() {
        version = header.version
        previousBlockHash = header.previousBlockHeaderHash
        merkleRoot = header.merkleRoot
        timestamp = header.timestamp
        bits = header.bits
        nonce = header.nonce

        headerHash = header.hash
        this.merkleBlock = merkleBlock
    }
}
