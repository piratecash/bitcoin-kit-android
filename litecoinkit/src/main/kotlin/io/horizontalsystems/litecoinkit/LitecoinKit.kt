package io.horizontalsystems.litecoinkit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.horizontalsystems.bitcoincore.AbstractKit
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.bitcoincore.BitcoinCoreBuilder
import io.horizontalsystems.bitcoincore.apisync.BCoinApi
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairApi
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairBlockHashFetcher
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairTransactionProvider
import io.horizontalsystems.bitcoincore.blocks.validators.BitsValidator
import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorChain
import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorSet
import io.horizontalsystems.bitcoincore.blocks.validators.LegacyTestNetDifficultyValidator
import io.horizontalsystems.bitcoincore.core.DoubleSha256Hasher
import io.horizontalsystems.bitcoincore.core.IPluginData
import io.horizontalsystems.bitcoincore.core.purpose
import io.horizontalsystems.bitcoincore.managers.ApiSyncStateManager
import io.horizontalsystems.bitcoincore.managers.Bip44RestoreKeyConverter
import io.horizontalsystems.bitcoincore.managers.Bip49RestoreKeyConverter
import io.horizontalsystems.bitcoincore.managers.Bip84RestoreKeyConverter
import io.horizontalsystems.bitcoincore.managers.Bip86RestoreKeyConverter
import io.horizontalsystems.bitcoincore.managers.BlockValidatorHelper
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.managers.ConnectionManager
import io.horizontalsystems.bitcoincore.models.Address
import io.horizontalsystems.bitcoincore.models.Checkpoint
import io.horizontalsystems.bitcoincore.models.TransactionDataSortType
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.models.WatchAddressPublicKey
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.*
import io.horizontalsystems.bitcoincore.network.peer.PeerAddressManager
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.network.peer.SharedPeerGroup
import io.horizontalsystems.bitcoincore.network.peer.SharedPeerGroupHolder
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.serializers.BlockHeaderParser
import io.horizontalsystems.bitcoincore.storage.CoreDatabase
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.Storage
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UnspentOutputInfo
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.transactions.builder.IInputSigner
import io.horizontalsystems.bitcoincore.transactions.builder.ISchnorrInputSigner
import io.horizontalsystems.bitcoincore.utils.AddressConverterChain
import io.horizontalsystems.bitcoincore.utils.Base58AddressConverter
import io.horizontalsystems.bitcoincore.utils.PaymentAddressParser
import io.horizontalsystems.bitcoincore.utils.SegwitAddressConverter
import io.horizontalsystems.bitcoincore.utils.SegwitLegacyAddressConverter
import io.horizontalsystems.hdwalletkit.HDExtendedKey
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.litecoinkit.mweb.LitecoinMwebEngine
import io.horizontalsystems.litecoinkit.mweb.LitecoinMwebEngineHandle
import io.horizontalsystems.litecoinkit.mweb.LitecoinMwebEngineRegistry
import io.horizontalsystems.litecoinkit.mweb.MwebBalance
import io.horizontalsystems.litecoinkit.mweb.MwebConfig
import io.horizontalsystems.litecoinkit.mweb.MwebError
import io.horizontalsystems.litecoinkit.mweb.MwebPublicSendOptions
import io.horizontalsystems.litecoinkit.mweb.MwebPublicTransactionBridge
import io.horizontalsystems.litecoinkit.mweb.MwebSendRequest
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressCodec
import io.horizontalsystems.litecoinkit.validators.LegacyDifficultyAdjustmentValidator
import io.horizontalsystems.litecoinkit.validators.ProofOfWorkValidator
import java.util.concurrent.ConcurrentHashMap

class LitecoinKit : AbstractKit {
    enum class NetworkType {
        MainNet,
        TestNet
    }

    interface Listener : BitcoinCore.Listener {
        fun onMwebBalanceUpdate(balance: MwebBalance) = Unit
        fun onMwebSyncStateUpdate(state: MwebSyncState) = Unit
        fun onMwebUtxosUpdate(utxos: List<MwebUtxo>) = Unit
    }

    override var bitcoinCore: BitcoinCore
    override var network: Network
    private var mwebEngineHandle: LitecoinMwebEngineHandle? = null
    private val mwebEngine: LitecoinMwebEngine?
        get() = mwebEngineHandle?.engine
    private var mwebEngineListener: MwebListenerAdapter? = null
    private val mwebPublicTransactionBridge: MwebPublicTransactionBridge by lazy { MwebBitcoinCoreBridge() }
    private lateinit var mwebAddressCodec: MwebAddressCodec

    var listener: Listener? = null
        set(value) {
            field = value
            bitcoinCore.listener = value
            setMwebListener(value)
        }

    constructor(
        context: Context,
        words: List<String>,
        passphrase: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        purpose: Purpose = Purpose.BIP44,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null,
        mwebConfig: MwebConfig? = null,
    ) : this(context, Mnemonic().toSeed(words, passphrase), walletId, networkType, peerSize, syncMode, confirmationsThreshold, purpose, sharedPeerGroupHolder = sharedPeerGroupHolder, mwebConfig = mwebConfig)

    constructor(
        context: Context,
        seed: ByteArray,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        purpose: Purpose = Purpose.BIP44,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null,
        mwebConfig: MwebConfig? = null,
    ) : this(context, HDExtendedKey(seed, purpose), purpose, walletId, networkType, peerSize, syncMode, confirmationsThreshold, sharedPeerGroupHolder = sharedPeerGroupHolder, mwebSeed = seed, mwebConfig = mwebConfig)

    /**
     * @constructor Creates and initializes the BitcoinKit
     * @param context The Android context
     * @param extendedKey HDExtendedKey that contains HDKey and version
     * @param walletId an arbitrary ID of type String.
     * @param networkType The network type. The default is MainNet.
     * @param peerSize The # of peer-nodes required. The default is 10 peers.
     * @param syncMode How the kit syncs with the blockchain. The default is SyncMode.Api().
     * @param confirmationsThreshold How many confirmations required to be considered confirmed. The default is 6 confirmations.
     */
    constructor(
        context: Context,
        extendedKey: HDExtendedKey,
        purpose: Purpose,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        iInputSigner: IInputSigner? = null,
        iSchnorrInputSigner: ISchnorrInputSigner? = null,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null,
        mwebSeed: ByteArray? = null,
        mwebConfig: MwebConfig? = null,
    ) {
        network = network(networkType)
        mwebAddressCodec = MwebAddressCodec(networkType)

        bitcoinCore = bitcoinCore(
            context = context,
            extendedKey = extendedKey,
            watchAddressPublicKey = null,
            networkType = networkType,
            walletId = walletId,
            syncMode = syncMode,
            purpose = purpose,
            peerSize = peerSize,
            confirmationsThreshold = confirmationsThreshold,
            iInputSigner = iInputSigner,
            iSchnorrInputSigner = iSchnorrInputSigner,
            sharedPeerGroupHolder = sharedPeerGroupHolder
        )
        mwebEngineHandle = mwebEngineHandle(context, mwebSeed, walletId, networkType, mwebConfig)
        setMwebListener(listener)
    }

    /**
     * @constructor Creates and initializes the BitcoinKit
     * @param context The Android context
     * @param watchAddress address for watching in read-only mode
     * @param walletId an arbitrary ID of type String.
     * @param networkType The network type. The default is MainNet.
     * @param peerSize The # of peer-nodes required. The default is 10 peers.
     * @param syncMode How the kit syncs with the blockchain. The default is SyncMode.Api().
     * @param confirmationsThreshold How many confirmations required to be considered confirmed. The default is 6 confirmations.
     */
    constructor(
        context: Context,
        watchAddress: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        iInputSigner: IInputSigner? = null,
        iSchnorrInputSigner: ISchnorrInputSigner? = null,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null
    ) {
        network = network(networkType)
        mwebAddressCodec = MwebAddressCodec(networkType)

        val address = parseAddress(watchAddress, network)
        val watchAddressPublicKey = WatchAddressPublicKey(address.lockingScriptPayload, address.scriptType)
        val purpose = address.scriptType.purpose ?: throw IllegalStateException("Not supported scriptType ${address.scriptType}")

        bitcoinCore = bitcoinCore(
            context = context,
            extendedKey = null,
            watchAddressPublicKey = watchAddressPublicKey,
            networkType = networkType,
            walletId = walletId,
            syncMode = syncMode,
            purpose = purpose,
            peerSize = peerSize,
            confirmationsThreshold = confirmationsThreshold,
            iInputSigner = iInputSigner,
            iSchnorrInputSigner = iSchnorrInputSigner,
            sharedPeerGroupHolder = sharedPeerGroupHolder
        )
    }

    /**
     * Starts public sync and, when enabled, the MWEB daemon on MwebConfig's IO dispatcher.
     *
     * This method blocks the caller while MWEB startup/native status checks run; do not call
     * it from Android main thread when MWEB is enabled.
     */
    override fun start() {
        super.start()
        mwebEngineHandle?.start()
    }

    /**
     * Stops public sync and the optional MWEB daemon.
     *
     * This method blocks the caller while MWEB native shutdown runs; do not call it from
     * Android main thread when MWEB is enabled.
     */
    override fun stop() {
        mwebEngineHandle?.stop()
        super.stop()
    }

    /**
     * Refreshes public Litecoin sync and restarts MWEB status/UTXO collection
     * without deleting the MWEB database or daemon data directory.
     *
     * This method blocks while MWEB refresh scheduling touches storage; do not call it from
     * Android main thread when MWEB is enabled.
     */
    override fun refresh() {
        super.refresh()
        mwebEngine?.refresh()
    }

    override fun dispose() {
        mwebEngine?.let { engine ->
            mwebEngineListener?.let { listener ->
                listener.dispose()
                engine.removeListener(listener)
            }
        }
        mwebEngineListener = null
        mwebEngineHandle?.release()
        mwebEngineHandle = null
        super.dispose()
    }

    val litecoinBalance: LitecoinBalance
        get() = LitecoinBalance(
            publicSpendable = balance.spendable,
            publicUnspendable = balance.unspendableTimeLocked + balance.unspendableNotRelayed,
            mweb = mwebEngine?.balance,
        )

    /**
     * Returns the current MWEB state, or null when MWEB is disabled.
     *
     * This property reads MWEB storage through blocking APIs; do not read it from Android
     * main thread when MWEB is enabled.
     */
    val mwebState: LitecoinMwebState?
        get() = mwebEngine?.let { engine ->
            LitecoinMwebState(
                balance = engine.balance,
                syncState = engine.syncState,
                debugInfo = engine.debugInfo(),
                utxos = engine.mwebUtxos(),
                pendingTransactions = engine.pendingTransactions(),
            )
        }

    /**
     * Returns a public or MWEB receive address.
     *
     * MWEB address generation can call native/storage code and blocks the caller; do not call
     * it from Android main thread for [LitecoinReceiveAddressType.Mweb].
     */
    fun receiveAddress(type: LitecoinReceiveAddressType): String {
        return when (type) {
            LitecoinReceiveAddressType.Public -> receiveAddress()
            LitecoinReceiveAddressType.Mweb -> requireMwebEngine().receiveAddress()
        }
    }

    fun isMwebAddress(address: String): Boolean {
        return mwebAddressCodec.isValid(address)
    }

    /**
     * Builds a fee/selection preview for public, peg-in, peg-out, or pure MWEB sends.
     *
     * MWEB previews call native/storage code and block the caller; do not call this method
     * from Android main thread when the source or destination uses MWEB.
     */
    fun sendInfo(
        value: Long,
        address: String,
        memo: String?,
        source: LitecoinSendSource,
        feeRate: Int,
        unspentOutputs: List<UnspentOutputInfo>?,
        pluginData: Map<Byte, IPluginData> = mapOf(),
        changeToFirstInput: Boolean,
        filters: UtxoFilters,
    ): LitecoinSendInfo {
        val mwebRequest = mwebRequest(source, address, value, feeRate)
        return if (mwebRequest == null) {
            LitecoinSendInfo.Public(
                super.sendInfo(
                    value = value,
                    address = address,
                    memo = memo,
                    senderPay = true,
                    feeRate = feeRate,
                    unspentOutputs = unspentOutputs,
                    pluginData = pluginData,
                    changeToFirstInput = changeToFirstInput,
                    filters = filters,
                )
            )
        } else {
            LitecoinSendInfo.Mweb(
                requireMwebEngine().sendInfo(
                    request = mwebRequest,
                    publicOptions = MwebPublicSendOptions(
                        unspentOutputs = unspentOutputs,
                        changeToFirstInput = changeToFirstInput,
                        rbfEnabled = false,
                        filters = filters,
                    ),
                    publicTransactionBridge = mwebPublicTransactionBridge,
                )
            )
        }
    }

    suspend fun send(
        address: String,
        memo: String?,
        value: Long,
        source: LitecoinSendSource,
        feeRate: Int,
        sortType: TransactionDataSortType,
        unspentOutputs: List<UnspentOutputInfo>? = null,
        pluginData: Map<Byte, IPluginData> = mapOf(),
        rbfEnabled: Boolean,
        changeToFirstInput: Boolean,
        filters: UtxoFilters,
    ): LitecoinSendResult {
        val mwebRequest = mwebRequest(source, address, value, feeRate)
        return if (mwebRequest == null) {
            LitecoinSendResult.Public(
                super.send(
                    address = address,
                    memo = memo,
                    value = value,
                    senderPay = true,
                    feeRate = feeRate,
                    sortType = sortType,
                    unspentOutputs = unspentOutputs,
                    pluginData = pluginData,
                    rbfEnabled = rbfEnabled,
                    changeToFirstInput = changeToFirstInput,
                    filters = filters,
                )
            )
        } else {
            LitecoinSendResult.Mweb(
                requireMwebEngine().send(
                    request = mwebRequest,
                    publicOptions = MwebPublicSendOptions(
                        unspentOutputs = unspentOutputs,
                        changeToFirstInput = changeToFirstInput,
                        rbfEnabled = rbfEnabled,
                        filters = filters,
                    ),
                    publicTransactionBridge = mwebPublicTransactionBridge,
                )
            )
        }
    }

    private fun mwebRequest(
        source: LitecoinSendSource,
        address: String,
        value: Long,
        feeRate: Int,
    ): MwebSendRequest? {
        val mwebDestination = isMwebAddress(address)
        return when (source) {
            LitecoinSendSource.Auto -> {
                if (mwebDestination) MwebSendRequest.PublicToMweb(address, value, feeRate) else null
            }
            LitecoinSendSource.Public -> {
                if (mwebDestination) MwebSendRequest.PublicToMweb(address, value, feeRate) else null
            }
            LitecoinSendSource.Mweb -> {
                if (mwebDestination) {
                    MwebSendRequest.MwebToMweb(address, value, feeRate)
                } else {
                    MwebSendRequest.MwebToPublic(address, value, feeRate)
                }
            }
        }
    }

    private fun requireMwebEngine(): LitecoinMwebEngine {
        return mwebEngine ?: throw MwebError.NativeUnavailable()
    }

    private fun setMwebListener(listener: Listener?) {
        val engine = mwebEngine ?: return
        mwebEngineListener?.let { current ->
            current.dispose()
            engine.removeListener(current)
        }
        mwebEngineListener = listener?.let(::MwebListenerAdapter)
        mwebEngineListener?.let(engine::addListener)
    }

    private fun mwebEngineHandle(
        context: Context,
        seed: ByteArray?,
        walletId: String,
        networkType: NetworkType,
        config: MwebConfig?,
    ): LitecoinMwebEngineHandle? {
        if (config == null) return null

        return LitecoinMwebEngineRegistry.acquire(
            context = context,
            seed = seed ?: throw IllegalArgumentException("MWEB requires a seed-derived LitecoinKit constructor; watch-only constructor cannot enable MWEB"),
            walletId = walletId,
            networkType = networkType,
            config = config,
        )
    }

    private class MwebListenerAdapter(private val listener: Listener) : LitecoinMwebEngine.Listener {
        @Volatile
        private var disposed = false

        fun dispose() {
            disposed = true
        }

        override fun onMwebBalanceUpdate(balance: MwebBalance) {
            if (!disposed) {
                listener.onMwebBalanceUpdate(balance)
            }
        }

        override fun onMwebSyncStateUpdate(state: MwebSyncState) {
            if (!disposed) {
                listener.onMwebSyncStateUpdate(state)
            }
        }

        override fun onMwebUtxosUpdate(utxos: List<MwebUtxo>) {
            if (!disposed) {
                listener.onMwebUtxosUpdate(utxos)
            }
        }
    }

    private inner class MwebBitcoinCoreBridge : MwebPublicTransactionBridge {
        override fun spendableUtxos(options: MwebPublicSendOptions): List<UnspentOutput> {
            val allSpendable = bitcoinCore.unspentOutputSelector.getAllSpendable(options.filters)
            val selectedInfos = options.unspentOutputs ?: return allSpendable
            return selectedInfos.mapNotNull { info ->
                allSpendable.firstOrNull { unspentOutput ->
                    unspentOutput.transaction.hash.contentEquals(info.transactionHash) &&
                        unspentOutput.output.index == info.outputIndex
                }
            }
        }

        override fun output(value: Long, address: String): TransactionOutput {
            return bitcoinCore.transactionOutput(value, address)
        }

        override fun changeOutput(
            value: Long,
            selectedUtxos: List<UnspentOutput>,
            changeToFirstInput: Boolean,
        ): TransactionOutput {
            val changeAddress = if (changeToFirstInput) {
                selectedUtxos.firstOrNull()?.let { bitcoinCore.address(it.publicKey) }
            } else {
                null
            } ?: bitcoinCore.address(bitcoinCore.changePublicKey())

            return TransactionOutput(
                value = value,
                index = 0,
                script = changeAddress.lockingScript,
                type = changeAddress.scriptType,
                address = changeAddress.stringValue,
                lockingScriptPayload = changeAddress.lockingScriptPayload,
            )
        }

        override fun serialize(transaction: FullTransaction): ByteArray {
            return bitcoinCore.serializeTransaction(transaction)
        }

        override fun processCreated(transaction: FullTransaction): FullTransaction {
            return bitcoinCore.processCreatedTransactionLocally(transaction)
        }

        override suspend fun sign(rawTransaction: ByteArray, selectedUtxos: List<UnspentOutput>): FullTransaction {
            return bitcoinCore.signRawTransaction(rawTransaction, selectedUtxos)
        }
    }

    private fun bitcoinCore(
        context: Context,
        extendedKey: HDExtendedKey?,
        watchAddressPublicKey: WatchAddressPublicKey?,
        networkType: NetworkType,
        walletId: String,
        syncMode: SyncMode,
        purpose: Purpose,
        peerSize: Int,
        confirmationsThreshold: Int,
        iInputSigner: IInputSigner?,
        iSchnorrInputSigner: ISchnorrInputSigner?,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null
    ): BitcoinCore {
        val database = CoreDatabase.getInstance(context, getDatabaseName(networkType, walletId, syncMode, purpose))
        val storage = Storage(database)
        val checkpoint = Checkpoint.resolveCheckpoint(syncMode, network, storage)
        val apiSyncStateManager = ApiSyncStateManager(storage, network.syncableFromApi && syncMode !is SyncMode.Full)
        val blockchairApi = BlockchairApi(network.blockchairChainId)
        val apiTransactionProvider = apiTransactionProvider(networkType, blockchairApi)
        val paymentAddressParser = PaymentAddressParser("litecoin", removeScheme = true)
        val blockValidatorSet = blockValidatorSet(storage, networkType)

        val coreBuilder = BitcoinCoreBuilder()

        val bitcoinCore = coreBuilder
            .setContext(context)
            .setExtendedKey(extendedKey)
            .setWatchAddressPublicKey(watchAddressPublicKey)
            .setPurpose(purpose)
            .setNetwork(network)
            .setCheckpoint(checkpoint)
            .setPaymentAddressParser(paymentAddressParser)
            .setPeerSize(peerSize)
            .setSyncMode(syncMode)
            .setSendType(BitcoinCore.SendType.API(blockchairApi))
            .setConfirmationThreshold(confirmationsThreshold)
            .setStorage(storage)
            .setApiTransactionProvider(apiTransactionProvider)
            .setApiSyncStateManager(apiSyncStateManager)
            .setBlockValidator(blockValidatorSet)
            .setAllowBroadcastFromUnsyncedPeers(true)
            .apply {
                if(iInputSigner != null && iSchnorrInputSigner != null) {
                    setSigners(iInputSigner, iSchnorrInputSigner)
                }
                if (sharedPeerGroupHolder != null) {
                    setSharedPeerGroupHolder(sharedPeerGroupHolder)
                }
            }
            .build()

        //  extending bitcoinCore

        val bech32AddressConverter = SegwitAddressConverter(network.addressSegwitHrp)
        val base58AddressConverter = Base58AddressConverter(network.addressVersion, network.addressScriptVersion)

        bitcoinCore.prependAddressConverter(bech32AddressConverter)

        when (purpose) {
            Purpose.BIP44 -> {
                bitcoinCore.addRestoreKeyConverter(Bip44RestoreKeyConverter(base58AddressConverter))
            }

            Purpose.BIP49 -> {
                bitcoinCore.addRestoreKeyConverter(Bip49RestoreKeyConverter(base58AddressConverter))
            }

            Purpose.BIP84 -> {
                bitcoinCore.addRestoreKeyConverter(Bip84RestoreKeyConverter(SegwitAddressConverter(network.addressSegwitHrp)))
            }

            Purpose.BIP86 -> {
                bitcoinCore.addRestoreKeyConverter(Bip86RestoreKeyConverter(SegwitAddressConverter(network.addressSegwitHrp)))
                bitcoinCore.addRestoreKeyConverter(Bip86RestoreKeyConverter(
                    SegwitLegacyAddressConverter(network.addressSegwitHrp)))
            }
        }

        return bitcoinCore
    }

    private fun parseAddress(address: String, network: Network): Address {
        val addressConverter = AddressConverterChain().apply {
            prependConverter(SegwitAddressConverter(network.addressSegwitHrp))
            prependConverter(Base58AddressConverter(network.addressVersion, network.addressScriptVersion))
        }
        return addressConverter.convert(address)
    }

    private fun network(networkType: NetworkType) = when (networkType) {
        NetworkType.MainNet -> MainNetLitecoin()
        NetworkType.TestNet -> TestNetLitecoin()
    }

    private fun blockValidatorSet(
        storage: Storage,
        networkType: NetworkType
    ): BlockValidatorSet {
        val blockValidatorSet = BlockValidatorSet()

        val proofOfWorkValidator = ProofOfWorkValidator(ScryptHasher())
        blockValidatorSet.addBlockValidator(proofOfWorkValidator)

        val blockValidatorChain = BlockValidatorChain()

        val blockHelper = BlockValidatorHelper(storage)

        if (networkType == NetworkType.MainNet) {
            blockValidatorChain.add(LegacyDifficultyAdjustmentValidator(blockHelper, heightInterval, targetTimespan, maxTargetBits))
            blockValidatorChain.add(BitsValidator())
        } else if (networkType == NetworkType.TestNet) {
            blockValidatorChain.add(LegacyDifficultyAdjustmentValidator(blockHelper, heightInterval, targetTimespan, maxTargetBits))
            blockValidatorChain.add(LegacyTestNetDifficultyValidator(storage, heightInterval, targetSpacing, maxTargetBits))
            blockValidatorChain.add(BitsValidator())
        }

        blockValidatorSet.addBlockValidator(blockValidatorChain)
        return blockValidatorSet
    }

    private fun apiTransactionProvider(
        networkType: NetworkType,
        blockchairApi: BlockchairApi
    ) = when (networkType) {
        NetworkType.MainNet -> {
            val blockchairBlockHashFetcher = BlockchairBlockHashFetcher(blockchairApi)
            BlockchairTransactionProvider(blockchairApi, blockchairBlockHashFetcher)
        }

        NetworkType.TestNet -> {
            BCoinApi("")
        }
    }

    companion object {

        const val maxTargetBits: Long = 0x1e0fffff      // Maximum difficulty
        const val targetSpacing = 150                   // 2.5 minutes per block.
        const val targetTimespan: Long = 302400         // 3.5 days per difficulty cycle, on average.
        const val heightInterval = targetTimespan / targetSpacing // 2016 blocks

        val defaultNetworkType: NetworkType = NetworkType.MainNet
        val defaultSyncMode: SyncMode = SyncMode.Api()
        const val defaultPeerSize: Int = 10
        const val defaultConfirmationsThreshold: Int = 6

        private val sharedGroups = ConcurrentHashMap<String, SharedPeerGroupHolder>()

        @Synchronized
        fun getOrCreateSharedPeerGroup(
            context: Context,
            walletId: String,
            networkType: NetworkType,
            peerSize: Int = defaultPeerSize
        ): SharedPeerGroupHolder {
            val key = "litecoin-${networkType.name}-$walletId"
            return sharedGroups.getOrPut(key) {
                val network = network(networkType)
                val peerManager = PeerManager()
                peerManager.setAllowBroadcastFromUnsyncedPeers(true)
                val networkMessageParser = NetworkMessageParser(network.magic)
                val networkMessageSerializer = NetworkMessageSerializer(network.magic)
                val bloomFilterManager = BloomFilterManager()
                val connectionManager = ConnectionManager.getInstance(context)

                val sharedDbName = "Litecoin-Shared-${networkType.name}-$walletId"
                val sharedDb = CoreDatabase.getInstance(context, sharedDbName)
                val sharedStorage = Storage(sharedDb)
                val peerAddressManager = PeerAddressManager(network, sharedStorage)

                val peerGroup = SharedPeerGroup(
                    hostManager = peerAddressManager,
                    network = network,
                    peerManager = peerManager,
                    peerSize = peerSize,
                    networkMessageParser = networkMessageParser,
                    networkMessageSerializer = networkMessageSerializer,
                    connectionManager = connectionManager,
                    localDownloadedBestBlockHeight = 0,
                    handleAddrMessage = true
                )
                peerAddressManager.listener = peerGroup

                val blockHeaderHasher = DoubleSha256Hasher()
                val transactionSerializer = BaseTransactionSerializer()

                networkMessageParser.add(AddrMessageParser())
                networkMessageParser.add(MerkleBlockMessageParser(BlockHeaderParser(blockHeaderHasher)))
                networkMessageParser.add(InvMessageParser())
                networkMessageParser.add(GetDataMessageParser())
                networkMessageParser.add(PingMessageParser())
                networkMessageParser.add(PongMessageParser())
                networkMessageParser.add(TransactionMessageParser(transactionSerializer))
                networkMessageParser.add(VerAckMessageParser())
                networkMessageParser.add(VersionMessageParser())
                networkMessageParser.add(RejectMessageParser())
                networkMessageParser.add(GetAddrMessageParser())

                networkMessageSerializer.add(FilterLoadMessageSerializer())
                networkMessageSerializer.add(GetBlocksMessageSerializer())
                networkMessageSerializer.add(InvMessageSerializer())
                networkMessageSerializer.add(GetDataMessageSerializer())
                networkMessageSerializer.add(MempoolMessageSerializer())
                networkMessageSerializer.add(PingMessageSerializer())
                networkMessageSerializer.add(PongMessageSerializer())
                networkMessageSerializer.add(TransactionMessageSerializer(transactionSerializer))
                networkMessageSerializer.add(VerAckMessageSerializer())
                networkMessageSerializer.add(VersionMessageSerializer())
                networkMessageSerializer.add(GetAddrMessageSerializer())

                SharedPeerGroupHolder(
                    peerGroup, peerManager, bloomFilterManager,
                    networkMessageParser, networkMessageSerializer
                )
            }
        }

        @Synchronized
        fun releaseSharedPeerGroup(walletId: String, networkType: NetworkType) {
            val key = "litecoin-${networkType.name}-$walletId"
            sharedGroups.remove(key)?.peerGroup?.forceStop()
        }

        private fun getDatabaseName(networkType: NetworkType, walletId: String, syncMode: SyncMode, purpose: Purpose): String =
            "Litecoin-${networkType.name}-$walletId-${syncMode.javaClass.simpleName}-${purpose.name}"

        /**
         * Deletes Litecoin public and MWEB databases for [walletId].
         *
         * All LitecoinKit instances for this wallet/network must be disposed first. If an
         * MWEB engine is still active, this method fails before deleting public data.
         */
        fun clear(context: Context, networkType: NetworkType, walletId: String) {
            LitecoinMwebEngineRegistry.clear(context, walletId, networkType)
            releaseSharedPeerGroup(walletId, networkType)
            try {
                val sharedDbName = "Litecoin-Shared-${networkType.name}-$walletId"
                SQLiteDatabase.deleteDatabase(context.getDatabasePath(sharedDbName))
            } catch (_: Exception) { }
            for (syncMode in listOf(SyncMode.Api(), SyncMode.Full(), SyncMode.Blockchair())) {
                for (purpose in Purpose.values())
                    try {
                        SQLiteDatabase.deleteDatabase(context.getDatabasePath(getDatabaseName(networkType, walletId, syncMode, purpose)))
                    } catch (ex: Exception) {
                        continue
                    }
            }
        }

        private fun network(networkType: NetworkType) = when (networkType) {
            NetworkType.MainNet -> MainNetLitecoin()
            NetworkType.TestNet -> TestNetLitecoin()
        }

        private fun addressConverter(purpose: Purpose, network: Network): AddressConverterChain {
            val addressConverter = AddressConverterChain()
            when (purpose) {
                Purpose.BIP44,
                Purpose.BIP49 -> {
                    addressConverter.prependConverter(
                        Base58AddressConverter(network.addressVersion, network.addressScriptVersion)
                    )
                }

                Purpose.BIP84,
                Purpose.BIP86 -> {
                    addressConverter.prependConverter(
                        SegwitAddressConverter(network.addressSegwitHrp)
                    )
                }
            }

            return addressConverter
        }

        fun firstAddress(
            seed: ByteArray,
            purpose: Purpose,
            networkType: NetworkType = NetworkType.MainNet,
        ): Address {
            return BitcoinCore.firstAddress(
                seed,
                purpose,
                network(networkType),
                addressConverter(purpose, network(networkType))
            )
        }

        fun firstAddress(
            extendedKey: HDExtendedKey,
            purpose: Purpose,
            networkType: NetworkType = NetworkType.MainNet,
        ): Address {
            return BitcoinCore.firstAddress(
                extendedKey,
                purpose,
                network(networkType),
                addressConverter(purpose, network(networkType))
            )
        }
    }

}
