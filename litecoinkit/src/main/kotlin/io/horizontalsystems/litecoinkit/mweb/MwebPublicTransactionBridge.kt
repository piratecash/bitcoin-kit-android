package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UnspentOutputInfo
import io.horizontalsystems.bitcoincore.storage.UtxoFilters

/** Public Litecoin send options reused when MWEB performs peg-in public coin selection. */
internal data class MwebPublicSendOptions(
    /** Optional wallet-selected public UTXOs; null means the bridge can choose all spendable UTXOs. */
    val unspentOutputs: List<UnspentOutputInfo>?,

    /** Match the existing LitecoinKit change-address policy for public change. */
    val changeToFirstInput: Boolean,

    /** Whether public peg-in inputs should use the RBF sequence. */
    val rbfEnabled: Boolean,

    /** Existing bitcoincore UTXO filters for public coin selection. */
    val filters: UtxoFilters,
)

/** Boundary between Litecoin MWEB logic and the existing public Litecoin bitcoincore instance. */
internal interface MwebPublicTransactionBridge {
    /** Returns public UTXOs available for peg-in funding. */
    fun spendableUtxos(options: MwebPublicSendOptions): List<UnspentOutput>

    /** Builds a canonical Litecoin output for peg-out. */
    fun output(value: Long, address: String): TransactionOutput

    /** Builds a canonical Litecoin change output for peg-in. */
    fun changeOutput(value: Long, selectedUtxos: List<UnspentOutput>, changeToFirstInput: Boolean): TransactionOutput

    /** Serializes a public transaction with bitcoincore's Litecoin serializer. */
    fun serialize(transaction: FullTransaction): ByteArray

    /** Persists and indexes the locally created canonical transaction. */
    fun processCreated(transaction: FullTransaction): FullTransaction

    /** Signs public peg-in inputs in a raw transaction template returned by mwebd. */
    suspend fun sign(rawTransaction: ByteArray, selectedUtxos: List<UnspentOutput>): FullTransaction
}
