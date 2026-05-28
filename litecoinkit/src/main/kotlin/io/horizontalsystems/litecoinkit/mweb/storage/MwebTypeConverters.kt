package io.horizontalsystems.litecoinkit.mweb.storage

import androidx.room.TypeConverter
import org.json.JSONArray

class MwebTypeConverters {
    @TypeConverter
    fun toStringList(value: String): List<String> {
        val jsonArray = JSONArray(value)
        return List(jsonArray.length()) { index -> jsonArray.getString(index) }
    }

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        val jsonArray = JSONArray()
        value.forEach { item -> jsonArray.put(item) }
        return jsonArray.toString()
    }
}
