package io.horizontalsystems.bitcoincore.models.converters

import androidx.room.TypeConverter
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import kotlinx.serialization.json.Json

class MerkleBlockConverter {
    @TypeConverter
    fun fromMerkleBlock(block: MerkleBlock?): String? {
        return block?.let { Json.encodeToString(MerkleBlock.serializer(), it) }
    }

    @TypeConverter
    fun toMerkleBlock(data: String?): MerkleBlock? {
        return data?.let { Json.decodeFromString(MerkleBlock.serializer(), it) }
    }
}