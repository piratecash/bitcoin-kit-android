package io.horizontalsystems.cosantakit

import com.eclipsesource.json.JsonValue
import io.horizontalsystems.bitcoincore.apisync.blockchair.Api
import io.horizontalsystems.bitcoincore.apisync.blockchair.FullApiTransaction
import io.horizontalsystems.bitcoincore.apisync.model.BlockHeaderItem
import io.horizontalsystems.bitcoincore.apisync.model.TransactionItem
import io.horizontalsystems.bitcoincore.core.IApiTransactionProvider
import io.horizontalsystems.bitcoincore.managers.ApiManager
import io.horizontalsystems.cosantakit.data.network.dto.AddressTxDto
import io.horizontalsystems.cosantakit.data.network.dto.BlockDto
import io.horizontalsystems.cosantakit.data.network.dto.CosantaTransactionResponse
import io.horizontalsystems.cosantakit.data.network.dto.TransactionItemDto
import io.horizontalsystems.cosantakit.data.network.dto.toBlockHeaderItem
import io.horizontalsystems.cosantakit.data.network.dto.toFullApiTransaction
import io.horizontalsystems.cosantakit.data.network.dto.toTransactionItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import timber.log.Timber

class CosantaApi : IApiTransactionProvider, Api {
    private companion object {
        const val HOST = "https://explorer.cosanta.net/"
        const val GAP_LIMIT = 20
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val apiManager = ApiManager(HOST)
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun transactions(addresses: List<String>, stopHeight: Int?): List<TransactionItem> {
        Timber.tag("COSA").d("Request transactions for ${addresses.size} addresses: [${addresses.first()}, ...]")

        return runBlocking {
            val allTransactions = mutableListOf<TransactionItem>()
            var leftGaps = GAP_LIMIT
            val results = addresses.map { address ->
                coroutineScope.async {
                    fetchTransactions(address, 0, 50)
                }
            }

            for (txs in results.awaitAll()) {
                if (txs.isEmpty()) {
                    leftGaps--
                    if (leftGaps <= 0) {
                        Timber.tag("COSA").d("Gaps limit reached")
                        break
                    }
                } else {
                    leftGaps = GAP_LIMIT
                }
                allTransactions.addAll(txs)
            }
            allTransactions
        }
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
        Timber.tag("COSA").d("getBlock for blockHash: $rawJson")
        json.decodeFromString<BlockDto>(rawJson).toBlockHeaderItem()
    } catch (ex: Exception) {
        ex.printStackTrace()
        null
    }

    override fun broadcastTransaction(rawTransactionHex: String): JsonValue {
        Timber.tag("COSA").d("Calling empty broadcastTransaction")
        return com.eclipsesource.json.Json.value("")
    }

    override suspend fun getTransactions(hashes: List<String>): List<FullApiTransaction> {
        return runBlocking {
            hashes.map { hash ->
                coroutineScope.async {
                    fetchTransaction(hash)
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun fetchTransaction(hash: String): FullApiTransaction? = try {
        val rawJson = apiManager.doOkHttpGetAsString("ext/gettx/$hash")!!
        json.decodeFromString<CosantaTransactionResponse>(rawJson).tx.toFullApiTransaction()
    } catch (ex: Exception) {
        ex.printStackTrace()
        null
    }

    private suspend fun fetchTransactions(
        addr: String,
        from: Int,
        to: Int
    ): List<TransactionItem> = try {
        Timber.tag("COSA").d("fetchTransactions for address: $addr")
        val rawJson = apiManager.doOkHttpGetAsString("ext/getaddresstxs/$addr/$from/$to")!!
        val results = json.decodeFromString<List<AddressTxDto>>(rawJson).map {
            coroutineScope.async {
                fetchTransactionInfo(it.txid)
            }
        }
        results.awaitAll().filterNotNull()
    } catch (ex: Exception) {
        ex.printStackTrace()
        emptyList()
    }

    private fun fetchTransactionInfo(transactionHash: String): TransactionItem? = try {
        Timber.tag("COSA").d("fetchTransactionInfo for transactionHash: $transactionHash")
        val rawJson = apiManager.doOkHttpGetAsString("ext/gettx/$transactionHash")!!
        json.decodeFromString<TransactionItemDto>(rawJson).toTransactionItem()
    } catch (ex: Exception) {
        ex.printStackTrace()
        null
    }
}
