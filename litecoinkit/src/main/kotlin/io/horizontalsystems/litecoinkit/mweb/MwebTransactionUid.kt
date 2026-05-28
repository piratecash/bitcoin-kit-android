package io.horizontalsystems.litecoinkit.mweb

internal object MwebTransactionUid {
    fun local(type: MwebTransactionType, id: String): String {
        return localPrefix(type) + id
    }

    fun localId(type: MwebTransactionType, uid: String): String? {
        val prefix = localPrefix(type)
        return uid.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    private fun localPrefix(type: MwebTransactionType): String {
        return "mweb-${type.name.lowercase()}:"
    }
}
