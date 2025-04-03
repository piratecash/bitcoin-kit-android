package io.horizontalsystems.cosantakit.messages

import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessageParser
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.cosantakit.models.CoinbaseTransaction
import io.horizontalsystems.cosantakit.models.Masternode
import io.horizontalsystems.cosantakit.models.Quorum

class MasternodeListDiffMessage(
    val baseBlockHash: ByteArray,
    val blockHash: ByteArray,
    val totalTransactions: Long,
    val merkleHashes: List<ByteArray>,
    val merkleFlags: ByteArray,
    val cbTx: FullTransaction,
    val deletedMNs: List<ByteArray>,
    val mnList: List<Masternode>,
    val deletedQuorums: List<Pair<Int, ByteArray>>,
    val quorumList: List<Quorum>
) : IMessage {

    override fun toString(): String {
        return "MnListDiffMessage(baseBlockHash=${baseBlockHash.toReversedHex()}, blockHash=${blockHash.toReversedHex()})"
    }

}

class MasternodeListDiffMessageParser : IMessageParser {
    override val command: String = "mnlistdiff"

    override fun parseMessage(input: BitcoinInputMarkable): IMessage {
        val baseBlockHash = input.readBytes(32)
        val blockHash = input.readBytes(32)

        // Read Merkle tree
        val totalTransactions = input.readUnsignedInt()
        val merkleHashesCount = input.readVarInt()
        val merkleHashes = mutableListOf<ByteArray>()
        repeat(merkleHashesCount.toInt()) {
            merkleHashes.add(input.readBytes(32))
        }
        val merkleFlagsCount = input.readVarInt()
        val merkleFlags = input.readBytes(merkleFlagsCount.toInt())

        // Read coinbase transaction
        val cbTx = CoinbaseTransaction.deserialize(input)
        val version = 0// (remove) input.readUnsignedShort()

        // Deleted MNs
        val deletedMNsCount = input.readVarInt()
        val deletedMNs = mutableListOf<ByteArray>()
        repeat(deletedMNsCount.toInt()) {
            deletedMNs.add(input.readBytes(32))
        }

        // Masternodes
        val mnListCount = input.readVarInt()
        val mnList = mutableListOf<Masternode>()
        repeat(mnListCount.toInt()) {
            mnList.add(Masternode(input))
        }

        // Deleted Quorums
        val deletedQuorumsCount = input.readVarInt()
        val deletedQuorums = mutableListOf<Pair<Int, ByteArray>>()
        repeat(deletedQuorumsCount.toInt()) {
            deletedQuorums.add(Pair(input.read(), input.readBytes(32)))
        }

        // Quorums
        val newQuorumsCount = input.readVarInt()
        val quorumList = mutableListOf<Quorum>()
        repeat(newQuorumsCount.toInt()) {
            quorumList.add(Quorum(input))
        }

        return MasternodeListDiffMessage(baseBlockHash, blockHash, totalTransactions, merkleHashes, merkleFlags, cbTx, deletedMNs, mnList, deletedQuorums, quorumList)
    }
}
