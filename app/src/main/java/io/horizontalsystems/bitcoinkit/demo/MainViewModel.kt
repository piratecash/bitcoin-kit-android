package io.horizontalsystems.bitcoinkit.demo

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.p.dogecoinkit.DogecoinKit
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.BitcoinCore.KitState
import io.horizontalsystems.bitcoincore.core.IPluginData
import io.horizontalsystems.bitcoincore.exceptions.AddressFormatException
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.managers.SendValueErrors
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.BitcoinSendInfo
import io.horizontalsystems.bitcoincore.models.BlockInfo
import io.horizontalsystems.bitcoincore.models.TransactionDataSortType
import io.horizontalsystems.bitcoincore.models.TransactionFilterType
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.hodler.HodlerData
import io.horizontalsystems.hodler.HodlerPlugin
import io.horizontalsystems.hodler.LockTimeInterval
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.LitecoinReceiveAddressType
import io.horizontalsystems.litecoinkit.LitecoinSendInfo
import io.horizontalsystems.litecoinkit.LitecoinSendResult
import io.horizontalsystems.litecoinkit.LitecoinSendSource
import io.horizontalsystems.litecoinkit.mweb.CoroutineMwebDispatcherProvider
import io.horizontalsystems.litecoinkit.mweb.MwebBalance
import io.horizontalsystems.litecoinkit.mweb.MwebConfig
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel : ViewModel(), LitecoinKit.Listener {

    enum class State {
        STARTED, STOPPED
    }

    private var transactionFilterType: TransactionFilterType? = null
    val types = listOf(null) + TransactionFilterType.values()

    val transactions = MutableLiveData<List<TransactionInfo>>()
    val balance = MutableLiveData<BalanceInfo>()
    val lastBlock = MutableLiveData<BlockInfo?>()
    val state = MutableLiveData<KitState>()
    val status = MutableLiveData<State>()
    val transactionRaw = MutableLiveData<String?>()
    val statusInfo = MutableLiveData<Map<String, Any>>()
    val mwebStatus = MutableLiveData<String>()
    val masternodeCount = MutableLiveData<Int>()
    lateinit var networkName: String
    private val disposables = CompositeDisposable()
    private var statsJob: Job? = null

    private var started = false
        set(value) {
            field = value
            status.value = (if (value) State.STARTED else State.STOPPED)
        }

    private lateinit var bitcoinKit: LitecoinKit

    private val walletId = "MyWallet"
    private val networkType = LitecoinKit.NetworkType.MainNet
    private val syncMode = BitcoinCore.SyncMode.Blockchair()

    fun init() {
        val words = BuildConfig.WORDS.split(" ")
        val passphrase = ""

        bitcoinKit = LitecoinKit(
            context = App.instance,
            words = words,
            passphrase = passphrase,
            walletId = walletId,
            syncMode = syncMode,
            networkType = networkType,
            confirmationsThreshold = 3,
            mwebConfig = MwebConfig(
                dispatcherProvider = CoroutineMwebDispatcherProvider(Dispatchers.IO),
                daemonClientFactory = DemoMwebDaemonClientFactory(),
            ),
//            purpose = Purpose.BIP84
        )

        bitcoinKit.listener = this

        networkName = bitcoinKit.networkName
        balance.value = bitcoinKit.balance
        updateMwebStatus()

        lastBlock.value = bitcoinKit.lastBlockInfo
        state.value = bitcoinKit.syncState
        started = false

        scheduleNetworkStatsUpdate()
        viewModelScope.launch {
            refreshNetworkStats()
        }
    }

    fun start() {
        if (started) return
        started = true

        bitcoinKit.start()
        startStatsUpdates()
    }

    fun stop() {
        if (!started) return
        started = false

        bitcoinKit.stop()
        stopStatsUpdates()
    }

    fun clear() {
        val wasRunning = statsJob?.isActive == true
        stopStatsUpdates()
        bitcoinKit.stop()
        LitecoinKit.clear(App.instance, networkType, walletId)

        init()
        if (wasRunning) {
            startStatsUpdates()
        }
    }


    fun showDebugInfo() {
        bitcoinKit.showDebugInfo()
    }

    fun showStatusInfo() {
        statusInfo.postValue(bitcoinKit.statusInfo())
    }

    //
    // DashKit Listener implementations
    //
    override fun onTransactionsUpdate(
        inserted: List<TransactionInfo>,
        updated: List<TransactionInfo>
    ) {
        setTransactionFilterType(transactionFilterType)
    }

    override fun onTransactionsDelete(hashes: List<String>) {
    }

    override fun onBalanceUpdate(balance: BalanceInfo) {
        this.balance.postValue(balance)
    }

    override fun onLastBlockInfoUpdate(blockInfo: BlockInfo) {
        this.lastBlock.postValue(blockInfo)
    }

    override fun onKitStateUpdate(state: KitState) {
        this.state.postValue(state)
        scheduleNetworkStatsUpdate()
    }

    override fun onMwebBalanceUpdate(balance: MwebBalance) {
        updateMwebStatus()
    }

    override fun onMwebSyncStateUpdate(state: MwebSyncState) {
        updateMwebStatus()
    }

    override fun onMwebUtxosUpdate(utxos: List<MwebUtxo>) {
        updateMwebStatus()
    }

    val receiveAddressLiveData = MutableLiveData<String>()
    val feeLiveData = MutableLiveData<Long?>()
    val errorLiveData = MutableLiveData<String?>()
    val addressLiveData = MutableLiveData<String?>()
    val amountLiveData = MutableLiveData<Long?>()
    var receiveAddressType = LitecoinReceiveAddressType.Public

    var sendSource = LitecoinSendSource.Auto
        set(value) {
            field = value
            updateFee()
        }

    var amount: Long? = null
        set(value) {
            field = value
            updateFee()
        }

    var address: String? = null
        set(value) {
            field = value
            updateFee()
        }

    var feePriority: FeePriority = FeePriority.Medium
        set(value) {
            field = value
            updateFee()
        }

    var timeLockInterval: LockTimeInterval? = null
        set(value) {
            field = value
            updateFee()
        }

    fun onReceiveClick() {
        receiveAddressLiveData.value = bitcoinKit.receiveAddress(receiveAddressType)
        updateMwebStatus()
    }

    fun onSendClick() {
        val sendAddress = address
        val sendAmount = amount

        when {
            sendAddress.isNullOrBlank() -> {
                errorLiveData.value = "Send address cannot be blank"
            }

            sendAmount == null -> {
                errorLiveData.value = "Send amount cannot be blank"
            }

            else -> {
                viewModelScope.launch {
                    try {
                        val result = bitcoinKit.send(
                            sendAddress,
                            null,
                            sendAmount,
                            source = sendSource,
                            feeRate = feePriority.feeRate,
                            sortType = TransactionDataSortType.Shuffle,
                            pluginData = getPluginData(),
                            rbfEnabled = true,
                            changeToFirstInput = false,
                            filters = UtxoFilters()
                        )

                        amountLiveData.value = null
                        feeLiveData.value = null
                        addressLiveData.value = null
                        errorLiveData.value = when (result) {
                            is LitecoinSendResult.Public -> "Transaction sent ${result.transaction.header.serializedTxInfo}"
                            is LitecoinSendResult.Mweb -> "MWEB transaction sent ${result.transaction.canonicalTransactionHash ?: result.transaction.outputIds.joinToString()}"
                        }
                        updateMwebStatus()
                    } catch (e: Exception) {
                        errorLiveData.value = when (e) {
                            is SendValueErrors.InsufficientUnspentOutputs,
                            is SendValueErrors.EmptyOutputs -> "Insufficient balance"

                            is AddressFormatException -> "Could not Format Address"
                            else -> e.message ?: "Failed to send transaction (${e.javaClass.name})"
                        }

                    }
                }
            }
        }
    }

    fun onMaxClick() {
        try {
            amountLiveData.value = bitcoinKit.maximumSpendableValue(
                address,
                null,
                feePriority.feeRate,
                null,
                getPluginData(),
                false,
                UtxoFilters()
            )
        } catch (e: Exception) {
            amountLiveData.value = 0
            errorLiveData.value = when (e) {

                is SendValueErrors.Dust,
                is SendValueErrors.EmptyOutputs -> "You need at least ${e.message} satoshis to make an transaction"
                is AddressFormatException -> "Could not Format Address"
                else -> e.message ?: "Maximum could not be calculated"
            }
        }
    }

    fun startStatsUpdates() {
        if (statsJob?.isActive == true) return
        statsJob = viewModelScope.launch {
            refreshNetworkStats()
            while (isActive) {
                delay(1_000)
                refreshNetworkStats()
            }
        }
    }

    fun stopStatsUpdates() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun scheduleNetworkStatsUpdate() {
        viewModelScope.launch {
            refreshNetworkStats()
        }
    }

    private suspend fun refreshNetworkStats() {
        /*val (masternodes, quorums) = withContext(Dispatchers.IO) {
            val masternodeTotal = bitcoinKit.masternodeCount()
            val quorumTotal = bitcoinKit.quorumCount()
            masternodeTotal to quorumTotal
        }
        masternodeCount.postValue(masternodes)*/
    }

    private fun updateFee() {
        try {
            feeLiveData.value = amount?.let {
                when (val sendInfo = fee(it, address)) {
                    is LitecoinSendInfo.Public -> sendInfo.sendInfo.fee
                    is LitecoinSendInfo.Mweb -> sendInfo.sendInfo.totalFee
                }
            }
        } catch (e: Exception) {
            errorLiveData.value = e.message ?: e.javaClass.simpleName
        }
    }

    private fun fee(value: Long, address: String? = null): LitecoinSendInfo {
        val destination = address ?: return LitecoinSendInfo.Public(publicFee(value, null))
        return bitcoinKit.sendInfo(
            value = value,
            address = destination,
            memo = null,
            source = sendSource,
            feeRate = feePriority.feeRate,
            unspentOutputs = null,
            pluginData = getPluginData(),
            changeToFirstInput = false,
            filters = UtxoFilters()
        )
    }

    private fun publicFee(value: Long, address: String? = null): BitcoinSendInfo {
        return bitcoinKit.sendInfo(
            value,
            address,
            null,
            feeRate = feePriority.feeRate,
            unspentOutputs = null,
            pluginData = getPluginData(),
            changeToFirstInput = false,
            filters = UtxoFilters()
        )
    }

    private fun updateMwebStatus() {
        val state = bitcoinKit.mwebState
        mwebStatus.postValue(
            if (state == null) {
                "MWEB disabled"
            } else {
                "MWEB ${state.syncState.mwebUtxosHeight}/${state.syncState.blockHeaderHeight}, balance ${state.balance.confirmed}/${state.balance.unconfirmed}"
            }
        )
    }

    private fun getPluginData(): MutableMap<Byte, IPluginData> {
        val pluginData = mutableMapOf<Byte, IPluginData>()
        timeLockInterval?.let {
            pluginData[HodlerPlugin.id] = HodlerData(it)
        }
        return pluginData
    }

    override fun onCleared() {
        stopStatsUpdates()
        super.onCleared()
    }

    fun onRawTransactionClick(transactionHash: String) {
        transactionRaw.postValue(bitcoinKit.getRawTransaction(transactionHash))
    }

    fun setTransactionFilterType(transactionFilterType: TransactionFilterType?) {
        this.transactionFilterType = transactionFilterType

        bitcoinKit.transactions(type = transactionFilterType)
            .subscribe(/* onSuccess = */ { txList: List<TransactionInfo> ->
                transactions.postValue(txList)
            }, /* onError = */ { e ->
                errorLiveData.value = e.message
            }).let {
                disposables.add(it)
            }
    }
}
