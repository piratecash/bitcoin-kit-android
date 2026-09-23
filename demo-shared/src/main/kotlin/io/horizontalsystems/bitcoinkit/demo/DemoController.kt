package io.horizontalsystems.bitcoinkit.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IPluginData
import io.horizontalsystems.bitcoincore.exceptions.AddressFormatException
import io.horizontalsystems.bitcoincore.managers.SendValueErrors
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.BlockInfo
import io.horizontalsystems.bitcoincore.models.TransactionFilterType
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hodler.HodlerData
import io.horizontalsystems.hodler.HodlerPlugin
import io.horizontalsystems.hodler.LockTimeInterval
import io.horizontalsystems.litecoinkit.LitecoinReceiveAddressType
import io.horizontalsystems.litecoinkit.LitecoinSendSource
import io.horizontalsystems.litecoinkit.mweb.MwebBalance
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Platform-free replacement for the demo's `MainViewModel`: owns the eight kits, presents one of
 * them at a time and exposes the whole demo as [DemoUiState] plus plain callbacks.
 *
 * Two scopes: [scope] is the host's lifetime scope and carries the lifecycle transitions;
 * [kitScope] is a per-kit child that carries the cancellable operations and dies with the kit it
 * was created for.
 */
class DemoController(
    private val dataDir: String,
    private val mwebDataDir: String,
    private val connectionManager: IConnectionManager,
    private val scope: CoroutineScope,
) : DemoKitListener {

    var uiState by mutableStateOf(DemoUiState())
        private set

    private val kits = mutableMapOf<KitKey, DemoKit>()
    private var activeKit: DemoKit? = null
    private var kitScope = newKitScope()
    private var statsJob: Job? = null
    private var formVersion = 0

    private val transitionMutex = Mutex()

    // Not Dispatchers.IO: the MWEB registry forbids start/stop from the dispatcher its own blocking
    // bridge re-dispatches onto. Serialised, because kit lifecycle calls must not interleave.
    private val kitDispatcher = Dispatchers.Default.limitedParallelism(1)

    fun init() = transition {
        hydrate(resolveAndCommit())
    }

    fun selectKit(type: KitType) = switchTo(type, uiState.purpose)

    fun selectPurpose(purpose: Purpose) = switchTo(uiState.kitType, purpose)

    fun start() = transition {
        if (uiState.running) return@transition
        val kit = resolveAndCommit()
        withContext(kitDispatcher) { kit.start() }
        uiState = uiState.copy(running = true)
        startStatsUpdates()
    }

    fun stop() = transition {
        if (!uiState.running) return@transition
        val kit = resolveAndCommit()
        withContext(kitDispatcher) { kit.stop() }
        uiState = uiState.copy(running = false)
        stopStatsUpdates()
    }

    /** `refresh()` starts the kit underneath, so the demo must present it as running. */
    fun refresh() = transition {
        val kit = resolveAndCommit()
        withContext(kitDispatcher) { kit.refresh() }
        if (!uiState.running) {
            uiState = uiState.copy(running = true)
            startStatsUpdates()
        }
    }

    /**
     * Wipes the selected kit's storage. Pausing before disposing is what stops the sender's own
     * retry timer, and the deletion is verified because the library discards every `delete()` result.
     */
    fun clear() = transition {
        val outgoing = resolveAndCommit()
        val type = outgoing.type
        release()
        acrossKitChange {
            val survivors = withContext(kitDispatcher) {
                outgoing.pauseNetwork()
                outgoing.dispose()
                evictKits(type, disposed = outgoing)
                outgoing.clear()
            }
            uiState = uiState.copy(running = false)
            hydrate(resolveAndCommit(type))
            if (survivors.isNotEmpty()) {
                uiState = uiState.copy(error = "Clear left files behind: ${survivors.joinToString()}")
            }
        }
    }

    fun showDebugInfo() = operation { kit ->
        withContext(kitDispatcher) { kit.showDebugInfo() }
    }

    fun showStatusInfo() = operation { kit ->
        val info = withContext(kitDispatcher) { kit.statusInfo() }
        publish(kit) { copy(statusInfo = info) }
    }

    fun setFilter(filter: TransactionFilterType?) {
        uiState = uiState.copy(filter = filter)
        loadTransactions(filter)
    }

    fun onReceiveClick() = operation { kit ->
        val type = uiState.receiveAddressType
        val mweb = type == LitecoinReceiveAddressType.Mweb
        val address = withContext(kitDispatcher) { kit.receiveAddress(mweb) }
        if (uiState.receiveAddressType == type) publish(kit) { copy(receiveAddress = address) }
        refreshMwebStatus(kit)
    }

    fun setReceiveAddressType(type: LitecoinReceiveAddressType) {
        uiState = uiState.copy(receiveAddressType = type, receiveAddress = "")
    }

    fun setSendSource(source: LitecoinSendSource) {
        uiState = uiState.copy(sendSource = source)
        formChanged()
    }

    fun setAddress(address: String) {
        uiState = uiState.copy(address = address)
        formChanged()
    }

    fun setAmount(amount: Long?) {
        uiState = uiState.copy(amount = amount)
        formChanged()
    }

    fun setFeePriority(priority: FeePriority) {
        uiState = uiState.copy(feePriority = priority)
        formChanged()
    }

    fun setLockTimeInterval(interval: LockTimeInterval?) {
        uiState = uiState.copy(lockTimeInterval = interval)
        formChanged()
    }

    /**
     * A transition, not an operation: the lock is taken in the same turn as the click, so a
     * send can no longer capture the amount the maximum is about to replace.
     */
    fun onMaxClick() = transition {
        val kit = activeKit ?: return@transition
        val version = formVersion
        val address = uiState.address.takeIf { it.isNotBlank() }
        val feeRate = uiState.feePriority.feeRate
        val pluginData = pluginData()
        val amount = try {
            withContext(kitDispatcher) { kit.maximumSpendableValue(address, feeRate, pluginData) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publishForm(kit, version) { copy(amount = 0, error = maximumErrorMessage(e)) }
            updateFee()
            return@transition
        }
        publishForm(kit, version) { copy(amount = amount) }
        updateFee()
    }

    fun onSendClick() = transition {
        val address = uiState.address
        val amount = uiState.amount
        val kit = activeKit
        when {
            address.isBlank() -> uiState = uiState.copy(error = "Send address cannot be blank")
            amount == null -> uiState = uiState.copy(error = "Send amount cannot be blank")
            kit != null -> send(kit, address, amount)
        }
    }

    fun onRawTransactionClick(transactionHash: String) = operation { kit ->
        val raw = withContext(kitDispatcher) { kit.rawTransaction(transactionHash) }
        publish(kit) { copy(rawTransaction = raw) }
    }

    fun dismissError() {
        uiState = uiState.copy(error = null)
    }

    fun dismissDialog() {
        uiState = uiState.copy(rawTransaction = null, statusInfo = null)
    }

    /**
     * Tears every kit down. Cancelling the host's scope stops the controller's own jobs and nothing
     * the kits own, so the teardown runs on a detached scope that deliberately outlives the host.
     */
    fun dispose() {
        CoroutineScope(SupervisorJob() + kitDispatcher).launch {
            transitionMutex.withLock {
                activeKit = null
                kits.values.forEach(::disposeQuietly)
                kits.clear()
            }
        }
    }

    override fun onTransactionsUpdate(
        kit: DemoKit,
        inserted: List<TransactionInfo>,
        updated: List<TransactionInfo>,
    ) {
        if (kit === activeKit) loadTransactions(uiState.filter)
    }

    override fun onTransactionsDelete(kit: DemoKit, hashes: List<String>) = Unit

    override fun onBalanceUpdate(kit: DemoKit, balance: BalanceInfo) {
        if (kit === activeKit) uiState = uiState.copy(balance = balance)
    }

    override fun onLastBlockInfoUpdate(kit: DemoKit, blockInfo: BlockInfo) {
        if (kit === activeKit) uiState = uiState.copy(lastBlock = blockInfo)
    }

    override fun onKitStateUpdate(kit: DemoKit, state: BitcoinCore.KitState) {
        if (kit !== activeKit) return
        uiState = uiState.copy(syncState = state)
        scheduleNetworkStatsUpdate()
    }

    override fun onMwebBalanceUpdate(kit: DemoKit, balance: MwebBalance) = mwebStatusChanged(kit)

    override fun onMwebSyncStateUpdate(kit: DemoKit, state: MwebSyncState) = mwebStatusChanged(kit)

    override fun onMwebUtxosUpdate(kit: DemoKit, utxos: List<MwebUtxo>) = mwebStatusChanged(kit)

    private fun newKitScope() =
        CoroutineScope(SupervisorJob(scope.coroutineContext.job) + Dispatchers.Main)

    /**
     * Acquires the transition lock on the caller's thread, so the gate closes in the same turn as
     * the click. The release is a completion handler rather than a `finally` because a job cancelled
     * before it is dispatched never runs its body — and would then hold the lock forever.
     */
    private fun transition(block: suspend () -> Unit) {
        if (!transitionMutex.tryLock()) return
        uiState = uiState.copy(transitionInFlight = true)
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState = uiState.copy(error = errorMessage(e))
            } finally {
                uiState = uiState.copy(transitionInFlight = false)
            }
        }.invokeOnCompletion { transitionMutex.unlock() }
    }

    /**
     * The two transitions that change kit identity: they cancel the operations of the outgoing kit
     * first and install the replacement scope only once the whole transition is over, so nothing
     * can slip an operation into the gap.
     */
    private suspend fun acrossKitChange(block: suspend () -> Unit) {
        kitScope.cancel()
        statsJob = null
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recover(e)
        } finally {
            kitScope = newKitScope()
        }
        if (uiState.running) startStatsUpdates()
    }

    /** Leaves the UI coherent after a failed transition: an error, nothing running, real numbers. */
    private suspend fun recover(error: Exception) {
        val messages = mutableListOf(errorMessage(error))
        activeKit?.let { kit ->
            try {
                withContext(kitDispatcher) { kit.pauseNetwork() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages += errorMessage(e)
            }
        }
        uiState = uiState.copy(running = false)
        try {
            hydrate(resolveAndCommit())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            messages += errorMessage(e)
        }
        uiState = uiState.copy(error = messages.joinToString("; "))
    }

    /** Both selectors land here: changing the purpose replaces the kit exactly like changing the kit. */
    private fun switchTo(type: KitType, purpose: Purpose) = transition {
        if (type == uiState.kitType && purpose == uiState.purpose && activeKit != null) return@transition

        val outgoing = activeKit
        val wasRunning = uiState.running
        release()
        acrossKitChange {
            outgoing?.let { withContext(kitDispatcher) { it.pauseNetwork() } }
            val kit = resolveAndCommit(type, purpose)
            if (wasRunning) {
                withContext(kitDispatcher) { kit.start() }
                uiState = uiState.copy(running = true)
            }
            hydrate(kit)
        }
    }

    /** Only a transition may reach this: it is the one place a kit is built and committed. */
    private suspend fun resolveAndCommit(
        type: KitType = uiState.kitType,
        purpose: Purpose = uiState.purpose,
    ): DemoKit {
        activeKit?.let { return it }
        val key = kitKey(type, purpose)
        val kit = kits[key] ?: withContext(kitDispatcher) {
            KitFactory.create(
                type = type,
                purpose = purpose,
                dataDir = dataDir,
                mwebDataDir = mwebDataDir,
                connectionManager = connectionManager,
                words = DemoConfig.WORDS.split(" "),
                walletId = KitFactory.WALLET_ID,
                scope = scope,
                listener = this@DemoController,
            )
        }.also { kits[key] = it }

        activeKit = kit
        uiState = uiState.committedTo(type, purpose)
        return kit
    }

    /** A kit that ignores the purpose opens one database, so it must not be cached once per purpose. */
    private fun kitKey(type: KitType, purpose: Purpose) =
        KitKey(type, purpose.takeIf { type.capabilities.purpose })

    private fun release() {
        activeKit = null
        uiState = uiState.released()
    }

    /**
     * Kits emit no current values on start, so the freshly presented one is read directly —
     * otherwise the previous kit's numbers would stay on screen until some listener happens to fire.
     */
    private suspend fun hydrate(kit: DemoKit) {
        val filter = uiState.filter
        val snapshot = withContext(kitDispatcher) {
            KitSnapshot(
                networkName = kit.networkName,
                balance = kit.balance,
                lastBlock = kit.lastBlockInfo,
                syncState = kit.syncState,
                mwebStatus = mwebStatus(kit),
                transactions = kit.transactions(filter),
            )
        }
        publish(kit) {
            copy(
                networkName = snapshot.networkName,
                balance = snapshot.balance,
                lastBlock = snapshot.lastBlock,
                syncState = snapshot.syncState,
                mwebStatus = snapshot.mwebStatus,
                transactions = snapshot.transactions,
            )
        }
    }

    private fun operation(block: suspend (DemoKit) -> Unit) {
        val kit = activeKit ?: return
        kitScope.launch {
            try {
                block(kit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish(kit) { copy(error = errorMessage(e)) }
            }
        }
    }

    /** Drops a result computed against a kit the controller no longer presents. */
    private fun publish(kit: DemoKit, block: DemoUiState.() -> DemoUiState) {
        if (kit === activeKit) uiState = uiState.block()
    }

    /** Drops a fee or maximum the user has already superseded by editing the send form. */
    private fun publishForm(kit: DemoKit, version: Int, block: DemoUiState.() -> DemoUiState) {
        if (version == formVersion) publish(kit, block)
    }

    /** Every edit invalidates the fee and the maximum computed against the previous input. */
    private fun formChanged() {
        formVersion++
        updateFee()
    }

    private fun loadTransactions(filter: TransactionFilterType?) = operation { kit ->
        val transactions = withContext(kitDispatcher) { kit.transactions(filter) }
        publish(kit) { copy(transactions = transactions) }
    }

    private fun mwebStatusChanged(kit: DemoKit) {
        if (kit === activeKit) operation { refreshMwebStatus(it) }
    }

    private suspend fun refreshMwebStatus(kit: DemoKit) {
        val status = withContext(kitDispatcher) { mwebStatus(kit) }
        publish(kit) { copy(mwebStatus = status) }
    }

    private fun mwebStatus(kit: DemoKit): String {
        val state = kit.mwebState ?: return "MWEB disabled"
        return "MWEB ${state.syncState.mwebUtxosHeight}/${state.syncState.blockHeaderHeight}, " +
            "balance ${state.balance.confirmed}/${state.balance.unconfirmed}"
    }

    private suspend fun send(kit: DemoKit, address: String, amount: Long) {
        val source = uiState.sendSource
        val feeRate = uiState.feePriority.feeRate
        val pluginData = pluginData()
        uiState = uiState.copy(sendInFlight = true)
        try {
            val outcome = withContext(kitDispatcher) {
                kit.send(address, amount, source, feeRate, pluginData)
            }
            uiState = uiState.copy(
                amount = null,
                fee = null,
                address = "",
                sendResult = outcome.message,
                error = null,
            )
            refreshMwebStatus(kit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = uiState.copy(error = sendErrorMessage(e))
        } finally {
            uiState = uiState.copy(sendInFlight = false)
        }
    }

    private fun updateFee() {
        val version = formVersion
        val amount = uiState.amount
        if (amount == null) {
            uiState = uiState.copy(fee = null)
            return
        }
        val address = uiState.address.takeIf { it.isNotBlank() }
        val source = uiState.sendSource
        val feeRate = uiState.feePriority.feeRate
        val pluginData = pluginData()
        operation { kit ->
            val fee = try {
                withContext(kitDispatcher) { kit.fee(amount, address, source, feeRate, pluginData) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Handled here rather than in operation() so a superseded input reports nothing.
                publishForm(kit, version) { copy(error = errorMessage(e)) }
                return@operation
            }
            publishForm(kit, version) { copy(fee = fee) }
        }
    }

    private fun pluginData(): Map<Byte, IPluginData> {
        val interval = uiState.lockTimeInterval?.takeIf { uiState.capabilities.hodler } ?: return emptyMap()
        return mapOf(HodlerPlugin.id to HodlerData(interval))
    }

    private fun startStatsUpdates() {
        if (statsJob?.isActive == true) return
        statsJob = kitScope.launch {
            refreshNetworkStats()
            while (isActive) {
                delay(STATS_INTERVAL_MILLIS)
                refreshNetworkStats()
            }
        }
    }

    private fun stopStatsUpdates() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun scheduleNetworkStatsUpdate() {
        kitScope.launch { refreshNetworkStats() }
    }

    private suspend fun refreshNetworkStats() {
        /*val (masternodes, quorums) = withContext(Dispatchers.IO) {
            val masternodeTotal = bitcoinKit.masternodeCount()
            val quorumTotal = bitcoinKit.quorumCount()
            masternodeTotal to quorumTotal
        }
        masternodeCount.postValue(masternodes)*/
    }

    /** `clear()` deletes every purpose's database of this kit, so no cached instance may outlive it. */
    private fun evictKits(type: KitType, disposed: DemoKit) {
        kits.keys.filter { it.type == type }.forEach { key ->
            kits.remove(key)?.takeIf { it !== disposed }?.let(::disposeQuietly)
        }
    }

    /** Tears down a kit the demo is discarding: nothing is left that could act on a failure. */
    private fun disposeQuietly(kit: DemoKit) {
        try {
            kit.pauseNetwork()
        } catch (_: Exception) {
        }
        try {
            kit.dispose()
        } catch (_: Exception) {
        }
    }

    /** Null purpose means the kit derives one way only — see [kitKey]. */
    private data class KitKey(val type: KitType, val purpose: Purpose?)

    private data class KitSnapshot(
        val networkName: String,
        val balance: BalanceInfo,
        val lastBlock: BlockInfo?,
        val syncState: BitcoinCore.KitState,
        val mwebStatus: String,
        val transactions: List<TransactionInfo>,
    )

    private companion object {
        const val STATS_INTERVAL_MILLIS = 1_000L
    }
}

internal fun errorMessage(e: Exception): String = e.message ?: e.javaClass.simpleName

internal fun sendErrorMessage(e: Exception): String = when (e) {
    is SendValueErrors.InsufficientUnspentOutputs,
    is SendValueErrors.EmptyOutputs -> "Insufficient balance"

    is AddressFormatException -> "Could not Format Address"
    else -> e.message ?: "Failed to send transaction (${e.javaClass.name})"
}

internal fun maximumErrorMessage(e: Exception): String = when (e) {
    is SendValueErrors.Dust,
    is SendValueErrors.EmptyOutputs -> "You need at least ${e.message} satoshis to make an transaction"

    is AddressFormatException -> "Could not Format Address"
    else -> e.message ?: "Maximum could not be calculated"
}
