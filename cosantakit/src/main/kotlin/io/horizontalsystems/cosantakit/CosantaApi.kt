package io.horizontalsystems.cosantakit

import co.touchlab.kermit.Logger
import com.eclipsesource.json.JsonValue
import io.horizontalsystems.bitcoincore.apisync.loadUntilConsecutiveEmpty
import io.horizontalsystems.bitcoincore.apisync.mapApiRequests
import io.horizontalsystems.bitcoincore.apisync.blockchair.Api
import io.horizontalsystems.bitcoincore.apisync.blockchair.FullApiTransaction
import io.horizontalsystems.bitcoincore.apisync.model.BlockHeaderItem
import io.horizontalsystems.bitcoincore.apisync.model.TransactionItem
import io.horizontalsystems.bitcoincore.core.IApiTransactionProvider
import io.horizontalsystems.bitcoincore.managers.ApiManager
import io.horizontalsystems.bitcoincore.network.NetworkErrorListenerHolder
import io.horizontalsystems.cosantakit.data.network.dto.AddressTxDto
import io.horizontalsystems.cosantakit.data.network.dto.BlockDto
import io.horizontalsystems.cosantakit.data.network.dto.CosantaTransactionResponse
import io.horizontalsystems.cosantakit.data.network.dto.TransactionItemDto
import io.horizontalsystems.cosantakit.data.network.dto.toBlockHeaderItem
import io.horizontalsystems.cosantakit.data.network.dto.toFullApiTransaction
import io.horizontalsystems.cosantakit.data.network.dto.toTransactionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val log = Logger.withTag("COSA")

class CosantaApi(
    networkErrorListener: NetworkErrorListenerHolder? = null
) : IApiTransactionProvider, Api {
    private companion object {
        const val HOST = "https://explorer.cosanta.net"
        const val GAP_LIMIT = 20
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val apiManager = ApiManager(HOST, networkErrorListener)

    override fun transactions(addresses: List<String>, stopHeight: Int?): List<TransactionItem> {
        log.d { "Request transactions for ${addresses.size} addresses: [${addresses.first()}, ...]" }

        return runBlocking(Dispatchers.IO) {
            val hashes = loadUntilConsecutiveEmpty(addresses, GAP_LIMIT) {
                fetchTransactionHashes(it, 0, 50)
            }
            mapApiRequests(hashes) {
                fetchTransactionInfo(it)
            }.filterNotNull()
        }
    }

    private fun fetchTransactionHashes(
        address: String,
        from: Int,
        to: Int
    ): List<String> = try {
        log.d { "fetchTransactionHashes for address: $address" }
        val rawJson = apiManager.doOkHttpGetAsString("ext/getaddresstxs/$address/$from/$to")
            ?: return emptyList()
        json.decodeFromString<List<AddressTxDto>>(rawJson).map {
            it.txid
        }
    } catch (ex: Exception) {
        ex.printStackTrace()
        emptyList()
    }

    override fun blockHashes(heights: List<Int>): Map<Int, String> = heights.mapNotNull { height ->
        getBlockHash(height)?.let { hash -> height to hash }
    }.toMap()

    override fun lastBlockHeader(): BlockHeaderItem {
        val lastBlockNum = apiManager.doOkHttpGetAsString("api/getblockcount")?.toInt()!!
        val lastBlockHash = getBlockHash(lastBlockNum)
        return getBlock(lastBlockHash!!)!!
    }

    private fun getBlockHash(blockHeight: Int): String? = try {
        apiManager.doOkHttpGetAsString("api/getblockhash?index=$blockHeight")!!
    } catch (ex: Exception) {
        ex.printStackTrace()
        null
    }

    private fun getBlock(blockHash: String): BlockHeaderItem? = try {
        val rawJson = apiManager.doOkHttpGetAsString("api/getblock?hash=$blockHash")!!
        log.d { "getBlock for blockHash: $rawJson" }
        json.decodeFromString<BlockDto>(rawJson).toBlockHeaderItem()
    } catch (ex: Exception) {
        ex.printStackTrace()
        null
    }

    override fun broadcastTransaction(rawTransactionHex: String): JsonValue {
        log.d { "Calling empty broadcastTransaction" }
        return com.eclipsesource.json.Json.value("")
    }

    override suspend fun getTransactions(hashes: List<String>): List<FullApiTransaction> {
        return withContext(Dispatchers.IO) {
            mapApiRequests(hashes) {
                fetchTransaction(it)
            }.filterNotNull()
        }
    }

    private fun fetchTransaction(hash: String): FullApiTransaction? = try {
        val rawJson = apiManager.doOkHttpGetAsString("ext/gettx/$hash")!!
        json.decodeFromString<CosantaTransactionResponse>(rawJson).tx.toFullApiTransaction()
    } catch (ex: Exception) {
        ex.printStackTrace()
        null
    }

    private fun fetchTransactionInfo(transactionHash: String): TransactionItem? = try {
        log.d { "fetchTransactionInfo for transactionHash: $transactionHash" }
        val rawJson = apiManager.doOkHttpGetAsString("ext/gettx/$transactionHash")!!
        json.decodeFromString<TransactionItemDto>(rawJson).toTransactionItem()
    } catch (ex: Exception) {
        ex.printStackTrace()
        null
    }
}
