package io.horizontalsystems.litecoinkit

import io.horizontalsystems.bitcoincore.AbstractKit
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.BitcoinCore.KitState
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
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IPluginData
import io.horizontalsystems.bitcoincore.core.purpose
import io.horizontalsystems.bitcoincore.managers.ApiSyncStateManager
import io.horizontalsystems.bitcoincore.managers.Bip44RestoreKeyConverter
import io.horizontalsystems.bitcoincore.managers.Bip49RestoreKeyConverter
import io.horizontalsystems.bitcoincore.managers.Bip84RestoreKeyConverter
import io.horizontalsystems.bitcoincore.managers.Bip86RestoreKeyConverter
import io.horizontalsystems.bitcoincore.managers.BlockValidatorHelper
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.models.Address
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.BlockInfo
import io.horizontalsystems.bitcoincore.models.Checkpoint
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionDataSortType
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.models.WatchAddressPublicKey
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.*
import io.horizontalsystems.bitcoincore.network.peer.PeerAddressManager
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.network.peer.SharedPeerGroup
import io.horizontalsystems.bitcoincore.network.peer.SharedPeerGroupHolder
import io.horizontalsystems.bitcoincore.serializers.BlockHeaderParser
import io.horizontalsystems.bitcoincore.storage.CoreDatabase
import io.horizontalsystems.bitcoincore.storage.DatabaseEncryption
import io.horizontalsystems.bitcoincore.storage.DatabaseMigrationResult
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
import io.horizontalsystems.litecoinkit.mweb.MwebFiles
import io.horizontalsystems.litecoinkit.mweb.MwebPublicPegInSender
import io.horizontalsystems.litecoinkit.mweb.MwebPublicSendConfig
import io.horizontalsystems.litecoinkit.mweb.MwebPublicSendOptions
import io.horizontalsystems.litecoinkit.mweb.MwebPublicTransactionStatus
import io.horizontalsystems.litecoinkit.mweb.MwebPublicTransactionBridge
import io.horizontalsystems.litecoinkit.mweb.MwebSendRequest
import io.horizontalsystems.litecoinkit.mweb.MwebSendInfo
import io.horizontalsystems.litecoinkit.mweb.MwebSendResult
import io.horizontalsystems.litecoinkit.mweb.MwebSignedRawTransaction
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
    private val bitcoinCoreListener = MwebAwareBitcoinCoreListener()
    private var mwebEngineListener: MwebListenerAdapter? = null
    private val mwebPublicTransactionBridge: MwebPublicTransactionBridge by lazy { MwebBitcoinCoreBridge() }
    private lateinit var mwebAddressCodec: MwebAddressCodec
    private lateinit var mwebPublicPegInSender: MwebPublicPegInSender

    var listener: Listener? = null
        set(value) {
            field = value
            bitcoinCore.listener = bitcoinCoreListener
            setMwebListener(value)
        }

    /**
     * @param dataDir Absolute path of the app's `databases` directory
     *   (`context.getDatabasePath("x").parent`); any other directory opens an empty database.
     * @param mwebDataDir Absolute path of `context.noBackupFilesDir`, where the MWEB daemon
     *   keeps its state; any other directory forces a full MWEB resync.
     */
    constructor(
        dataDir: String,
        mwebDataDir: String,
        connectionManager: IConnectionManager,
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
        mwebPublicSendConfig: MwebPublicSendConfig = MwebPublicSendConfig(),
    ) : this(
        dataDir = dataDir,
        mwebDataDir = mwebDataDir,
        connectionManager = connectionManager,
        seed = Mnemonic().toSeed(words, passphrase),
        walletId = walletId,
        networkType = networkType,
        peerSize = peerSize,
        syncMode = syncMode,
        confirmationsThreshold = confirmationsThreshold,
        purpose = purpose,
        sharedPeerGroupHolder = sharedPeerGroupHolder,
        mwebConfig = mwebConfig,
        mwebPublicSendConfig = mwebPublicSendConfig,
    )

    constructor(
        dataDir: String,
        mwebDataDir: String,
        databaseKey: ByteArray,
        connectionManager: IConnectionManager,
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
        mwebPublicSendConfig: MwebPublicSendConfig = MwebPublicSendConfig(),
    ) : this(
        dataDir, mwebDataDir, databaseKey, connectionManager, Mnemonic().toSeed(words, passphrase),
        walletId, networkType, peerSize, syncMode, confirmationsThreshold, purpose,
        sharedPeerGroupHolder, mwebConfig, mwebPublicSendConfig,
    )

    /**
     * @param dataDir Absolute path of the app's `databases` directory
     *   (`context.getDatabasePath("x").parent`); any other directory opens an empty database.
     * @param mwebDataDir Absolute path of `context.noBackupFilesDir`, where the MWEB daemon
     *   keeps its state; any other directory forces a full MWEB resync.
     */
    constructor(
        dataDir: String,
        mwebDataDir: String,
        connectionManager: IConnectionManager,
        seed: ByteArray,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        purpose: Purpose = Purpose.BIP44,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null,
        mwebConfig: MwebConfig? = null,
        mwebPublicSendConfig: MwebPublicSendConfig = MwebPublicSendConfig(),
    ) : this(
        dataDir = dataDir,
        mwebDataDir = mwebDataDir,
        connectionManager = connectionManager,
        extendedKey = HDExtendedKey(seed, purpose),
        purpose = purpose,
        walletId = walletId,
        networkType = networkType,
        peerSize = peerSize,
        syncMode = syncMode,
        confirmationsThreshold = confirmationsThreshold,
        sharedPeerGroupHolder = sharedPeerGroupHolder,
        mwebSeed = seed,
        mwebConfig = mwebConfig,
        mwebPublicSendConfig = mwebPublicSendConfig,
    )

    constructor(
        dataDir: String,
        mwebDataDir: String,
        databaseKey: ByteArray,
        connectionManager: IConnectionManager,
        seed: ByteArray,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        purpose: Purpose = Purpose.BIP44,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null,
        mwebConfig: MwebConfig? = null,
        mwebPublicSendConfig: MwebPublicSendConfig = MwebPublicSendConfig(),
    ) : this(
        dataDir, mwebDataDir, connectionManager, HDExtendedKey(seed, purpose), purpose, null,
        walletId, networkType, peerSize, syncMode, confirmationsThreshold, null, null,
        sharedPeerGroupHolder, seed, mwebConfig, mwebPublicSendConfig, databaseKey,
    )

    /**
     * @constructor Creates and initializes the BitcoinKit
     * @param dataDir Absolute path of the app's `databases` directory
     *   (`context.getDatabasePath("x").parent`); any other directory opens an empty database.
     * @param mwebDataDir Absolute path of `context.noBackupFilesDir`, where the MWEB daemon
     *   keeps its state; any other directory forces a full MWEB resync.
     * @param connectionManager Source of network connectivity state.
     * @param extendedKey HDExtendedKey that contains HDKey and version
     * @param walletId an arbitrary ID of type String.
     * @param networkType The network type. The default is MainNet.
     * @param peerSize The # of peer-nodes required. The default is 10 peers.
     * @param syncMode How the kit syncs with the blockchain. The default is SyncMode.Api().
     * @param confirmationsThreshold How many confirmations required to be considered confirmed. The default is 6 confirmations.
     */
    constructor(
        dataDir: String,
        mwebDataDir: String,
        connectionManager: IConnectionManager,
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
        mwebPublicSendConfig: MwebPublicSendConfig = MwebPublicSendConfig(),
    ) : this(
        dataDir, mwebDataDir, connectionManager, extendedKey, purpose, null, walletId, networkType,
        peerSize, syncMode, confirmationsThreshold, iInputSigner, iSchnorrInputSigner,
        sharedPeerGroupHolder, mwebSeed, mwebConfig, mwebPublicSendConfig, null,
    )

    constructor(
        dataDir: String,
        mwebDataDir: String,
        databaseKey: ByteArray,
        connectionManager: IConnectionManager,
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
        mwebPublicSendConfig: MwebPublicSendConfig = MwebPublicSendConfig(),
    ) : this(
        dataDir, mwebDataDir, connectionManager, extendedKey, purpose, null, walletId, networkType,
        peerSize, syncMode, confirmationsThreshold, iInputSigner, iSchnorrInputSigner,
        sharedPeerGroupHolder, mwebSeed, mwebConfig, mwebPublicSendConfig, databaseKey,
    )

    /**
     * @constructor Creates and initializes the BitcoinKit
     * @param dataDir Absolute path of the app's `databases` directory
     *   (`context.getDatabasePath("x").parent`); any other directory opens an empty database.
     * @param mwebDataDir Absolute path of `context.noBackupFilesDir`, where the MWEB daemon
     *   keeps its state; any other directory forces a full MWEB resync.
     * @param connectionManager Source of network connectivity state.
     * @param watchAddress address for watching in read-only mode
     * @param walletId an arbitrary ID of type String.
     * @param networkType The network type. The default is MainNet.
     * @param peerSize The # of peer-nodes required. The default is 10 peers.
     * @param syncMode How the kit syncs with the blockchain. The default is SyncMode.Api().
     * @param confirmationsThreshold How many confirmations required to be considered confirmed. The default is 6 confirmations.
     */
    constructor(
        dataDir: String,
        mwebDataDir: String,
        connectionManager: IConnectionManager,
        watchAddress: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        iInputSigner: IInputSigner? = null,
        iSchnorrInputSigner: ISchnorrInputSigner? = null,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null,
        mwebPublicSendConfig: MwebPublicSendConfig = MwebPublicSendConfig(),
    ) : this(
        dataDir, mwebDataDir, connectionManager, null, null, watchAddress, walletId, networkType,
        peerSize, syncMode, confirmationsThreshold, iInputSigner, iSchnorrInputSigner,
        sharedPeerGroupHolder, null, null, mwebPublicSendConfig, null,
    )

    constructor(
        dataDir: String,
        mwebDataDir: String,
        databaseKey: ByteArray,
        connectionManager: IConnectionManager,
        watchAddress: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        iInputSigner: IInputSigner? = null,
        iSchnorrInputSigner: ISchnorrInputSigner? = null,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null,
        mwebPublicSendConfig: MwebPublicSendConfig = MwebPublicSendConfig(),
    ) : this(
        dataDir, mwebDataDir, connectionManager, null, null, watchAddress, walletId, networkType,
        peerSize, syncMode, confirmationsThreshold, iInputSigner, iSchnorrInputSigner,
        sharedPeerGroupHolder, null, null, mwebPublicSendConfig, databaseKey,
    )

    private constructor(
        dataDir: String,
        mwebDataDir: String,
        connectionManager: IConnectionManager,
        extendedKey: HDExtendedKey?,
        purpose: Purpose?,
        watchAddress: String?,
        walletId: String,
        networkType: NetworkType,
        peerSize: Int,
        syncMode: SyncMode,
        confirmationsThreshold: Int,
        iInputSigner: IInputSigner?,
        iSchnorrInputSigner: ISchnorrInputSigner?,
        sharedPeerGroupHolder: SharedPeerGroupHolder?,
        mwebSeed: ByteArray?,
        mwebConfig: MwebConfig?,
        mwebPublicSendConfig: MwebPublicSendConfig,
        databaseKey: ByteArray?,
    ) {
        network = network(networkType)
        mwebAddressCodec = MwebAddressCodec(networkType)
        mwebPublicPegInSender = MwebPublicPegInSender(mwebDataDir, walletId, networkType, mwebAddressCodec, mwebPublicSendConfig)

        val address = watchAddress?.let { parseAddress(it, network) }
        val watchAddressPublicKey = address?.let { WatchAddressPublicKey(it.lockingScriptPayload, it.scriptType) }
        val resolvedPurpose = purpose ?: address?.scriptType?.purpose
            ?: throw IllegalStateException("Wallet purpose is unavailable")

        bitcoinCore = bitcoinCore(
            dataDir = dataDir,
            connectionManager = connectionManager,
            extendedKey = extendedKey,
            watchAddressPublicKey = watchAddressPublicKey,
            networkType = networkType,
            walletId = walletId,
            syncMode = syncMode,
            purpose = resolvedPurpose,
            peerSize = peerSize,
            confirmationsThreshold = confirmationsThreshold,
            iInputSigner = iInputSigner,
            iSchnorrInputSigner = iSchnorrInputSigner,
            sharedPeerGroupHolder = sharedPeerGroupHolder,
            databaseKey = databaseKey,
        )
        bitcoinCore.listener = bitcoinCoreListener
        mwebEngineHandle = mwebEngineHandle(
            dataDir, mwebDataDir, mwebSeed, walletId, networkType, mwebConfig, databaseKey,
        )
        setMwebListener(listener)
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
        syncPublicMwebTransactions()
    }

    /**
     * Stops public sync and the MWEB daemon without releasing the engine — [start] revives both.
     *
     * This method blocks the caller while MWEB native shutdown runs; do not call it from
     * Android main thread when MWEB is enabled.
     */
    override fun pauseNetwork() {
        try {
            stopMweb()
        } finally {
            super.pauseNetwork()
        }
    }

    /**
     * Stops public sync and the optional MWEB daemon.
     *
     * This method blocks the caller while MWEB native shutdown runs; do not call it from
     * Android main thread when MWEB is enabled.
     */
    override fun stop() {
        try {
            stopMweb()
        } finally {
            super.stop()
        }
    }

    /** Native shutdown can throw, and every stop still has to run. */
    private fun stopMweb() {
        try {
            mwebPublicPegInSender.stop()
        } finally {
            mwebEngineHandle?.stop()
        }
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
        syncPublicMwebTransactions()
    }

    override fun dispose() {
        mwebEngine?.let { engine ->
            mwebEngineListener?.let { listener ->
                listener.dispose()
                engine.removeListener(listener)
            }
        }
        mwebEngineListener = null
        mwebPublicPegInSender.stop()
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
                transactions = engine.transactions(),
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
            val publicOptions = MwebPublicSendOptions(
                unspentOutputs = unspentOutputs,
                changeToFirstInput = changeToFirstInput,
                rbfEnabled = false,
                filters = filters,
            )
            LitecoinSendInfo.Mweb(
                mwebSendInfo(mwebRequest, publicOptions)
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
            val publicOptions = MwebPublicSendOptions(
                unspentOutputs = unspentOutputs,
                changeToFirstInput = changeToFirstInput,
                rbfEnabled = rbfEnabled,
                filters = filters,
            )
            LitecoinSendResult.Mweb(
                mwebSend(mwebRequest, publicOptions)
            )
        }
    }

    suspend fun createSignedMwebTransaction(
        address: String,
        value: Long,
        source: LitecoinSendSource,
        feeRate: Int,
        rbfEnabled: Boolean,
        changeToFirstInput: Boolean,
        filters: UtxoFilters,
        unspentOutputs: List<UnspentOutputInfo>? = null,
    ): MwebSignedRawTransaction {
        val request = mwebRequest(source, address, value, feeRate)
            ?: throw IllegalArgumentException("Address and source do not describe an MWEB transaction")
        val publicOptions = MwebPublicSendOptions(
            unspentOutputs = unspentOutputs,
            changeToFirstInput = changeToFirstInput,
            rbfEnabled = rbfEnabled,
            filters = filters,
        )
        return mwebCreateSignedTransaction(request, publicOptions)
    }

    suspend fun broadcastMwebRawTransaction(rawTransaction: ByteArray): String {
        return mwebEngine?.broadcastRawTransaction(rawTransaction)
            ?: mwebPublicPegInSender.broadcastRawTransaction(rawTransaction)
    }

    fun isMwebRawTransaction(rawTransaction: ByteArray): Boolean =
        LitecoinRawTransactionClassifier.isMweb(rawTransaction)

    private fun mwebSendInfo(
        request: MwebSendRequest,
        publicOptions: MwebPublicSendOptions,
    ): MwebSendInfo {
        return when (request) {
            is MwebSendRequest.PublicToMweb -> mwebEngine?.sendInfo(
                request = request,
                publicOptions = publicOptions,
                publicTransactionBridge = mwebPublicTransactionBridge,
            ) ?: mwebPublicPegInSender.sendInfo(
                request = request,
                publicOptions = publicOptions,
                publicTransactionBridge = mwebPublicTransactionBridge,
            )
            is MwebSendRequest.MwebToPublic,
            is MwebSendRequest.MwebToMweb -> requireMwebEngine().sendInfo(
                request = request,
                publicOptions = publicOptions,
                publicTransactionBridge = mwebPublicTransactionBridge,
            )
        }
    }

    private suspend fun mwebSend(
        request: MwebSendRequest,
        publicOptions: MwebPublicSendOptions,
    ): MwebSendResult {
        return when (request) {
            is MwebSendRequest.PublicToMweb -> mwebEngine?.send(
                request = request,
                publicOptions = publicOptions,
                publicTransactionBridge = mwebPublicTransactionBridge,
            ) ?: mwebPublicPegInSender.send(
                request = request,
                publicOptions = publicOptions,
                publicTransactionBridge = mwebPublicTransactionBridge,
            )
            is MwebSendRequest.MwebToPublic,
            is MwebSendRequest.MwebToMweb -> requireMwebEngine().send(
                request = request,
                publicOptions = publicOptions,
                publicTransactionBridge = mwebPublicTransactionBridge,
            )
        }
    }

    private suspend fun mwebCreateSignedTransaction(
        request: MwebSendRequest,
        publicOptions: MwebPublicSendOptions,
    ): MwebSignedRawTransaction {
        return when (request) {
            is MwebSendRequest.PublicToMweb -> mwebEngine?.createSignedTransaction(
                request = request,
                publicOptions = publicOptions,
                publicTransactionBridge = mwebPublicTransactionBridge,
            ) ?: mwebPublicPegInSender.createSignedTransaction(
                request = request,
                publicOptions = publicOptions,
                publicTransactionBridge = mwebPublicTransactionBridge,
            )
            is MwebSendRequest.MwebToPublic,
            is MwebSendRequest.MwebToMweb -> requireMwebEngine().createSignedTransaction(
                request = request,
                publicOptions = publicOptions,
                publicTransactionBridge = mwebPublicTransactionBridge,
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

    private fun syncPublicMwebTransactions() {
        mwebEngine?.syncPublicTransactions(mwebPublicTransactionBridge)
    }

    private fun syncPublicMwebTransactionsAsync() {
        mwebEngine?.syncPublicTransactionsAsync(mwebPublicTransactionBridge)
    }

    private inner class MwebAwareBitcoinCoreListener : BitcoinCore.Listener {
        override fun onTransactionsUpdate(inserted: List<TransactionInfo>, updated: List<TransactionInfo>) {
            syncPublicMwebTransactionsAsync()
            listener?.onTransactionsUpdate(inserted, updated)
        }

        override fun onTransactionsDelete(hashes: List<String>) {
            listener?.onTransactionsDelete(hashes)
        }

        override fun onBalanceUpdate(balance: BalanceInfo) {
            listener?.onBalanceUpdate(balance)
        }

        override fun onLastBlockInfoUpdate(blockInfo: BlockInfo) {
            listener?.onLastBlockInfoUpdate(blockInfo)
        }

        override fun onKitStateUpdate(state: KitState) {
            listener?.onKitStateUpdate(state)
        }
    }

    private fun mwebEngineHandle(
        dataDir: String,
        mwebDataDir: String,
        seed: ByteArray?,
        walletId: String,
        networkType: NetworkType,
        config: MwebConfig?,
        databaseKey: ByteArray?,
    ): LitecoinMwebEngineHandle? {
        if (config == null) return null

        return LitecoinMwebEngineRegistry.acquire(
            dataDir = dataDir,
            mwebDataDir = mwebDataDir,
            seed = seed ?: throw IllegalArgumentException("MWEB requires a seed-derived LitecoinKit constructor; watch-only constructor cannot enable MWEB"),
            databaseKey = databaseKey,
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
            transaction.header.status = Transaction.Status.NEW
            return bitcoinCore.processCreatedTransaction(transaction)
        }

        override fun transactionStatus(hash: String): MwebPublicTransactionStatus? {
            return bitcoinCore.getTransaction(hash)?.let { transaction ->
                MwebPublicTransactionStatus(
                    height = transaction.blockHeight,
                    timestamp = transaction.timestamp,
                )
            }
        }

        override suspend fun sign(rawTransaction: ByteArray, selectedUtxos: List<UnspentOutput>): FullTransaction {
            return bitcoinCore.signRawTransaction(rawTransaction, selectedUtxos)
        }
    }

    private fun bitcoinCore(
        dataDir: String,
        connectionManager: IConnectionManager,
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
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null,
        databaseKey: ByteArray? = null,
    ): BitcoinCore {
        sharedPeerGroupHolder?.requireDatabaseKey(databaseKey)
        val database = CoreDatabase.getInstance(
            dataDir,
            getDatabaseName(networkType, walletId, syncMode, purpose),
            databaseKey,
        )
        val storage = Storage(database)
        val checkpoint = Checkpoint.resolveCheckpoint(syncMode, network, storage)
        val apiSyncStateManager = ApiSyncStateManager(storage, network.syncableFromApi && syncMode !is SyncMode.Full)
        val blockchairApi = BlockchairApi(network.blockchairChainId, networkErrorHolder)
        val apiTransactionProvider = apiTransactionProvider(networkType, blockchairApi)
        val paymentAddressParser = PaymentAddressParser("litecoin", removeScheme = true)
        val blockValidatorSet = blockValidatorSet(storage, networkType)

        val transactionSerializer = LitecoinTransactionSerializer()
        val coreBuilder = BitcoinCoreBuilder()

        val bitcoinCore = coreBuilder
            .setConnectionManager(connectionManager)
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
            .setTransactionSerializer(transactionSerializer)
            .setApiTransactionProvider(apiTransactionProvider)
            .setApiSyncStateManager(apiSyncStateManager)
            .setNetworkErrorHolder(networkErrorHolder)
            .setBlockValidator(blockValidatorSet)
            .setAllowBroadcastFromUnsyncedPeers(true)
            .setRequestUnknownBlocks(syncMode is SyncMode.Blockchair)
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
            BCoinApi("", networkErrorHolder)
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

        fun getOrCreateSharedPeerGroup(
            dataDir: String,
            connectionManager: IConnectionManager,
            walletId: String,
            networkType: NetworkType,
            peerSize: Int = defaultPeerSize
        ): SharedPeerGroupHolder = getOrCreateSharedPeerGroupInternal(
            dataDir, null, connectionManager, walletId, networkType, peerSize,
        )

        fun getOrCreateSharedPeerGroup(
            dataDir: String,
            databaseKey: ByteArray,
            connectionManager: IConnectionManager,
            walletId: String,
            networkType: NetworkType,
            peerSize: Int = defaultPeerSize,
        ): SharedPeerGroupHolder = getOrCreateSharedPeerGroupInternal(
            dataDir, databaseKey, connectionManager, walletId, networkType, peerSize,
        )

        @Synchronized
        private fun getOrCreateSharedPeerGroupInternal(
            dataDir: String,
            databaseKey: ByteArray?,
            connectionManager: IConnectionManager,
            walletId: String,
            networkType: NetworkType,
            peerSize: Int,
        ): SharedPeerGroupHolder {
            val key = migrationId(networkType, walletId)
            sharedGroups[key]?.let { holder ->
                holder.requireDatabaseKey(databaseKey)
                return holder
            }
            val network = network(networkType)
            val peerManager = PeerManager()
            peerManager.setAllowBroadcastFromUnsyncedPeers(true)
            val networkMessageParser = NetworkMessageParser(network.magic)
            val networkMessageSerializer = NetworkMessageSerializer(network.magic)
            val bloomFilterManager = BloomFilterManager()

            val sharedDb = CoreDatabase.getInstance(dataDir, sharedDbName(networkType, walletId), databaseKey)
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
            val transactionSerializer = LitecoinTransactionSerializer()

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

            return SharedPeerGroupHolder(
                peerGroup, peerManager, bloomFilterManager,
                networkMessageParser, networkMessageSerializer, databaseKey,
            ).also { sharedGroups[key] = it }
        }

        @Synchronized
        fun releaseSharedPeerGroup(walletId: String, networkType: NetworkType) {
            val key = migrationId(networkType, walletId)
            sharedGroups.remove(key)?.peerGroup?.forceStop()
        }

        internal fun getDatabaseName(networkType: NetworkType, walletId: String, syncMode: SyncMode, purpose: Purpose): String =
            "Litecoin-${networkType.name}-$walletId-${syncMode.javaClass.simpleName}-${purpose.name}"

        internal fun sharedDbName(networkType: NetworkType, walletId: String): String =
            "Litecoin-Shared-${networkType.name}-$walletId"

        internal fun databaseNames(networkType: NetworkType, walletId: String): List<String> = buildList {
            add(sharedDbName(networkType, walletId))
            DatabaseEncryption.supportedSyncModes().forEach { syncMode ->
                Purpose.values().forEach { purpose -> add(getDatabaseName(networkType, walletId, syncMode, purpose)) }
            }
            add(MwebFiles.databaseName(networkType, walletId))
        }

        /** Must be called before constructing any kit for this wallet. */
        suspend fun migrateDatabases(
            dataDir: String,
            networkType: NetworkType,
            walletId: String,
            databaseKey: ByteArray,
        ): DatabaseMigrationResult {
            LitecoinMwebEngineRegistry.checkInactive(walletId, networkType)
            MwebPublicPegInSender.checkCanClear(walletId, networkType)
            check(!sharedGroups.containsKey(migrationId(networkType, walletId))) {
                "Dispose the active LitecoinKit instances before migrating their databases"
            }
            return DatabaseEncryption.migrateDatabases(
                dataDir = dataDir,
                databaseNames = databaseNames(networkType, walletId),
                migrationId = migrationId(networkType, walletId),
                databaseKey = databaseKey,
            )
        }

        /**
         * Deletes Litecoin public and MWEB databases for [walletId].
         *
         * All LitecoinKit instances for this wallet/network must be disposed first. If an
         * MWEB engine is still active, this method fails before deleting public data.
         */
        fun clear(dataDir: String, mwebDataDir: String, networkType: NetworkType, walletId: String) {
            LitecoinMwebEngineRegistry.checkInactive(walletId, networkType)
            MwebPublicPegInSender.checkCanClear(walletId, networkType)
            releaseSharedPeerGroup(walletId, networkType)
            DatabaseEncryption.clearDatabases(
                dataDir = dataDir,
                databaseNames = databaseNames(networkType, walletId),
                migrationId = migrationId(networkType, walletId),
            )
            MwebFiles.clearDaemonData(mwebDataDir, networkType, walletId)
        }

        private fun migrationId(networkType: NetworkType, walletId: String): String =
            "litecoin-${networkType.name}-$walletId"

        /**
         * Deletes only MWEB scan storage, wallet daemon data, and public-send daemon data for [walletId].
         *
         * Use this when the MWEB restore point changes without resetting public
         * Litecoin BIP44/BIP49/BIP84/BIP86 databases. Active public-send daemons
         * must be stopped by stopping or disposing their LitecoinKit instances first.
         */
        fun clearMweb(dataDir: String, mwebDataDir: String, networkType: NetworkType, walletId: String) {
            LitecoinMwebEngine.clear(dataDir, mwebDataDir, networkType, walletId)
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
