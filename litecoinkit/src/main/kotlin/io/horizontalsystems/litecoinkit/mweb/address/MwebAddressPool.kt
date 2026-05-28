package io.horizontalsystems.litecoinkit.mweb.address

import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClient

class MwebAddressPool(
    private val codec: MwebAddressCodec,
    private val daemonClient: MwebDaemonClient,
    private val storage: MwebAddressStorage,
) {
    fun changeAddress(): String = address(CHANGE_INDEX)

    fun receiveAddress(): String = address(FIRST_RECEIVE_INDEX)

    fun addresses(fromIndex: Int, toIndex: Int): List<String> {
        require(fromIndex >= CHANGE_INDEX) { "Address index must be non-negative" }
        require(toIndex >= fromIndex) { "Invalid address range" }

        fillMissingAddresses(fromIndex, toIndex)
        return (fromIndex..toIndex).map { index ->
            checkNotNull(storage.address(index)) { "Address $index was not generated" }.address
        }
    }

    private fun address(index: Int): String {
        return addresses(index, index).first()
    }

    private fun fillMissingAddresses(fromIndex: Int, toIndex: Int) {
        val missingIndexes = (fromIndex..toIndex).filter { storage.address(it) == null }
        val firstMissingIndex = missingIndexes.firstOrNull() ?: return
        val lastMissingIndex = missingIndexes.last()

        val generated = daemonClient.addresses(firstMissingIndex, lastMissingIndex)
        val expectedSize = lastMissingIndex - firstMissingIndex + 1
        check(generated.size == expectedSize) { "Daemon returned ${generated.size} addresses, expected $expectedSize" }

        val records = generated.mapIndexed { offset, address ->
            codec.validate(address)
            MwebAddressRecord(index = firstMissingIndex + offset, address = address, used = false)
        }
        storage.save(records)
    }

    companion object {
        const val CHANGE_INDEX = 0
        const val FIRST_RECEIVE_INDEX = 1
    }
}

data class MwebAddressRecord(
    val index: Int,
    val address: String,
    val used: Boolean,
)

interface MwebAddressStorage {
    fun address(index: Int): MwebAddressRecord?
    fun addresses(): List<MwebAddressRecord>
    fun save(records: List<MwebAddressRecord>)
}

class InMemoryMwebAddressStorage : MwebAddressStorage {
    private val records = linkedMapOf<Int, MwebAddressRecord>()

    override fun address(index: Int): MwebAddressRecord? = records[index]

    override fun addresses(): List<MwebAddressRecord> = records.values.sortedBy { it.index }

    override fun save(records: List<MwebAddressRecord>) {
        records.forEach { record ->
            this.records[record.index] = record
        }
    }
}
