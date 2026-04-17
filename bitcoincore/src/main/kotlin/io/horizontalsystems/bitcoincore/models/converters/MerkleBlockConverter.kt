package io.horizontalsystems.bitcoincore.models.converters

import androidx.room.TypeConverter
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import kotlinx.serialization.json.Json
import timber.log.Timber

class MerkleBlockConverter {
    private val json = Json { allowStructuredMapKeys = true }

    @TypeConverter
    fun fromMerkleBlock(block: MerkleBlock?): String? {
        return block?.let {
            try {
                json.encodeToString(MerkleBlock.serializer(), it)
            } catch (e: Exception) {
                val keys = it.associatedTransactionHashes.keys.take(5).map { key -> "${key::class.simpleName}(${key.bytes.contentToString()})" }
                Timber.e(e, "Failed to serialize MerkleBlock: hash=${it.blockHash.contentToString()}, txCount=${it.associatedTransactionHashes.size}, keys=$keys")
                null
            }
        }
    }

    @TypeConverter
    fun toMerkleBlock(data: String?): MerkleBlock? {
        return data?.let {
            try {
                json.decodeFromString(MerkleBlock.serializer(), it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to deserialize MerkleBlock from: ${it.take(200)}")
                null
            }
        }
    }
}