package io.horizontalsystems.bitcoinkit.demo

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.BitcoinCore.KitState
import io.horizontalsystems.bitcoincore.core.IPluginData
import io.horizontalsystems.bitcoincore.exceptions.AddressFormatException
import io.horizontalsystems.bitcoincore.managers.SendValueErrors
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.BitcoinSendInfo
import io.horizontalsystems.bitcoincore.models.BlockInfo
import io.horizontalsystems.bitcoincore.models.TransactionDataSortType
import io.horizontalsystems.bitcoincore.models.TransactionFilterType
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.cosantakit.CosantaKit
import io.horizontalsystems.dashkit.DashKit
import io.horizontalsystems.hodler.HodlerData
import io.horizontalsystems.hodler.HodlerPlugin
import io.horizontalsystems.hodler.LockTimeInterval
import io.horizontalsystems.piratecashkit.PirateCashKit
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.launch

class MainViewModel : ViewModel(), PirateCashKit.Listener {

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
    lateinit var networkName: String
    private val disposables = CompositeDisposable()

    private var started = false
        set(value) {
            field = value
            status.value = (if (value) State.STARTED else State.STOPPED)
        }

    private lateinit var bitcoinKit: PirateCashKit

    private val walletId = "MyWallet"
    private val networkType = PirateCashKit.NetworkType.MainNet
    private val syncMode = BitcoinCore.SyncMode.Blockchair()

    fun init() {
        val words = BuildConfig.WORDS.split(" ")
        val passphrase = ""

        bitcoinKit = PirateCashKit(
            context = App.instance,
            words = words,
            passphrase = passphrase,
            walletId = walletId,
            syncMode = syncMode,
            networkType = networkType,
            confirmationsThreshold = 3,
        )

        bitcoinKit.listener = this

        networkName = bitcoinKit.networkName
        balance.value = bitcoinKit.balance

        lastBlock.value = bitcoinKit.lastBlockInfo
        state.value = bitcoinKit.syncState

        started = false
    }

    fun start() {
        if (started) return
        started = true

        bitcoinKit.start()
    }

    fun clear() {
        bitcoinKit.stop()
        PirateCashKit.clear(App.instance, networkType, walletId)

        init()
    }


    fun showDebugInfo() {
        bitcoinKit.showDebugInfo()
    }

    fun showStatusInfo() {
        statusInfo.postValue(bitcoinKit.statusInfo())
    }

    //
    // PirateCashKit Listener implementations
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
    }

    val receiveAddressLiveData = MutableLiveData<String>()
    val feeLiveData = MutableLiveData<Long?>()
    val errorLiveData = MutableLiveData<String?>()
    val addressLiveData = MutableLiveData<String?>()
    val amountLiveData = MutableLiveData<Long?>()

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
        receiveAddressLiveData.value = bitcoinKit.receiveAddress()
    }

    fun onSendClick() {
        when {
            address.isNullOrBlank() -> {
                errorLiveData.value = "Send address cannot be blank"
            }

            amount == null -> {
                errorLiveData.value = "Send amount cannot be blank"
            }

            else -> {
                viewModelScope.launch {
                    try {
                        val transaction = bitcoinKit.send(
                            address!!,
                            null,
                            amount!!,
                            feeRate = feePriority.feeRate,
                            sortType = TransactionDataSortType.Shuffle,
                            pluginData = getPluginData(),
                            rbfEnabled = true
                        )

                        amountLiveData.value = null
                        feeLiveData.value = null
                        addressLiveData.value = null
                        errorLiveData.value =
                            "Transaction sent ${transaction.header.serializedTxInfo}"
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
                getPluginData()
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

    private fun updateFee() {
        try {
            feeLiveData.value = amount?.let {
                fee(it, address).fee
            }
        } catch (e: Exception) {
            errorLiveData.value = e.message ?: e.javaClass.simpleName
        }
    }

    private fun fee(value: Long, address: String? = null): BitcoinSendInfo {
        return bitcoinKit.sendInfo(
            value,
            address,
            null,
            feeRate = feePriority.feeRate,
            unspentOutputs = null,
            pluginData = getPluginData()
        )
    }

    private fun getPluginData(): MutableMap<Byte, IPluginData> {
        val pluginData = mutableMapOf<Byte, IPluginData>()
        timeLockInterval?.let {
            pluginData[HodlerPlugin.id] = HodlerData(it)
        }
        return pluginData
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
