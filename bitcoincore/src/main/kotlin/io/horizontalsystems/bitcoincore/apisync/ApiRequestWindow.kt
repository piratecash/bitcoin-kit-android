package io.horizontalsystems.bitcoincore.apisync

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

private const val MAX_CONCURRENT_REQUESTS = 4

suspend fun <Input, Output> mapApiRequests(
    inputs: List<Input>,
    request: suspend (Input) -> Output
): List<Output> {
    val results = mutableListOf<IndexedValue<Output>>()
    executeRequests(inputs, request) {
        results.add(it)
        true
    }

    return results.sortedBy { it.index }.map { it.value }
}

suspend fun <Input, Output> loadUntilConsecutiveEmpty(
    inputs: List<Input>,
    emptyLimit: Int,
    request: suspend (Input) -> List<Output>
): List<Output> {
    require(emptyLimit > 0)

    val outputs = mutableListOf<Output>()
    val completedRequests = mutableMapOf<Int, List<Output>>()
    var consecutiveEmpty = 0
    var nextResultIndex = 0

    executeRequests(inputs, request) { completed ->
        completedRequests[completed.index] = completed.value

        while (true) {
            val fetched = completedRequests.remove(nextResultIndex) ?: break
            nextResultIndex++

            if (fetched.isEmpty()) {
                consecutiveEmpty++
                if (consecutiveEmpty == emptyLimit) {
                    return@executeRequests false
                }
            } else {
                consecutiveEmpty = 0
                outputs.addAll(fetched)
            }
        }

        true
    }

    return outputs
}

private suspend fun <Input, Output> executeRequests(
    inputs: List<Input>,
    request: suspend (Input) -> Output,
    onComplete: (IndexedValue<Output>) -> Boolean
) = coroutineScope {
    val activeRequests = mutableMapOf<Int, Deferred<IndexedValue<Output>>>()
    var nextRequestIndex = 0

    fun launchNext() {
        val index = nextRequestIndex++
        activeRequests[index] = async {
            IndexedValue(index, request(inputs[index]))
        }
    }

    repeat(minOf(MAX_CONCURRENT_REQUESTS, inputs.size)) {
        launchNext()
    }

    while (activeRequests.isNotEmpty()) {
        val completed = select {
            activeRequests.values.forEach { deferred ->
                deferred.onAwait { it }
            }
        }
        activeRequests.remove(completed.index)

        if (!onComplete(completed)) {
            activeRequests.values.forEach { it.cancel() }
            return@coroutineScope
        }

        if (nextRequestIndex < inputs.size) {
            launchNext()
        }
    }
}
