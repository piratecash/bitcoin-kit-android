package io.horizontalsystems.cosantakit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.horizontalsystems.bitcoincore.AbstractKit
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.bitcoincore.BitcoinCoreBuilder
import io.horizontalsystems.bitcoincore.DustCalculator
import io.horizontalsystems.bitcoincore.apisync.BiApiTransactionProvider
import io.horizontalsystems.bitcoincore.apisync.InsightApi
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairBlockHashFetcher
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairTransactionProvider
import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorSet
import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.managers.ApiSyncStateManager
import io.horizontalsystems.bitcoincore.managers.Bip44RestoreKeyConverter
import io.horizontalsystems.bitcoincore.managers.UnspentOutputSelector
import io.horizontalsystems.bitcoincore.models.Address
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.BlockInfo
import io.horizontalsystems.bitcoincore.models.Checkpoint
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.bitcoincore.models.WatchAddressPublicKey
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.storage.CoreDatabase
import io.horizontalsystems.bitcoincore.storage.Storage
import io.horizontalsystems.bitcoincore.transactions.TransactionSizeCalculator
import io.horizontalsystems.bitcoincore.utils.Base58AddressConverter
import io.horizontalsystems.bitcoincore.utils.MerkleBranch
import io.horizontalsystems.bitcoincore.utils.PaymentAddressParser
import io.horizontalsystems.cosantakit.core.CosantaTransactionInfoConverter
import io.horizontalsystems.cosantakit.core.SingleSha256Hasher
import io.horizontalsystems.cosantakit.instantsend.BLS
import io.horizontalsystems.cosantakit.instantsend.InstantSendFactory
import io.horizontalsystems.cosantakit.instantsend.InstantSendLockValidator
import io.horizontalsystems.cosantakit.instantsend.InstantTransactionManager
import io.horizontalsystems.cosantakit.instantsend.TransactionLockVoteValidator
import io.horizontalsystems.cosantakit.instantsend.instantsendlock.InstantSendLockHandler
import io.horizontalsystems.cosantakit.instantsend.instantsendlock.InstantSendLockManager
import io.horizontalsystems.cosantakit.instantsend.transactionlockvote.TransactionLockVoteHandler
import io.horizontalsystems.cosantakit.instantsend.transactionlockvote.TransactionLockVoteManager
import io.horizontalsystems.cosantakit.managers.ConfirmedUnspentOutputProvider
import io.horizontalsystems.cosantakit.managers.MasternodeListManager
import io.horizontalsystems.cosantakit.managers.MasternodeListSyncer
import io.horizontalsystems.cosantakit.managers.MasternodeSortedList
import io.horizontalsystems.cosantakit.managers.QuorumListManager
import io.horizontalsystems.cosantakit.managers.QuorumSortedList
import io.horizontalsystems.cosantakit.masternodelist.MasternodeListMerkleRootCalculator
import io.horizontalsystems.cosantakit.masternodelist.MerkleRootCreator
import io.horizontalsystems.cosantakit.masternodelist.MerkleRootHasher
import io.horizontalsystems.cosantakit.masternodelist.QuorumListMerkleRootCalculator
import io.horizontalsystems.cosantakit.messages.CosantaBlockHeaderParser
import io.horizontalsystems.cosantakit.messages.CosantaCoinMerkleBlockMessage
import io.horizontalsystems.cosantakit.messages.GetMasternodeListDiffMessageSerializer
import io.horizontalsystems.cosantakit.messages.ISLockMessageParser
import io.horizontalsystems.cosantakit.messages.MasternodeListDiffMessageParser
import io.horizontalsystems.cosantakit.messages.TransactionLockMessageParser
import io.horizontalsystems.cosantakit.messages.TransactionLockVoteMessageParser
import io.horizontalsystems.cosantakit.messages.TransactionMessageParser
import io.horizontalsystems.cosantakit.models.CosantaTransactionInfo
import io.horizontalsystems.cosantakit.models.InstantTransactionState
import io.horizontalsystems.cosantakit.storage.CosantaKitDatabase
import io.horizontalsystems.cosantakit.storage.CosantaStorage
import io.horizontalsystems.cosantakit.tasks.PeerTaskFactory
import io.horizontalsystems.cosantakit.validators.CosantaProofOfStakeValidator
import io.horizontalsystems.cosantakit.validators.CosantaProofOfWorkValidator
import io.horizontalsystems.hdwalletkit.HDExtendedKey
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hdwalletkit.Mnemonic

class CosantaKit : AbstractKit, IInstantTransactionDelegate, BitcoinCore.Listener {
    enum class NetworkType {
        MainNet,
        TestNet
    }

    interface Listener {
        fun onTransactionsUpdate(inserted: List<TransactionInfo>, updated: List<TransactionInfo>)
        fun onTransactionsDelete(hashes: List<String>)
        fun onBalanceUpdate(balance: BalanceInfo)
        fun onLastBlockInfoUpdate(blockInfo: BlockInfo)
        fun onKitStateUpdate(state: BitcoinCore.KitState)
    }

    var listener: Listener? = null

    override var bitcoinCore: BitcoinCore
    override var network: Network

    private val cosantaStorage: CosantaStorage
    private val instantSend: InstantSend
    private val cosantaTransactionInfoConverter: CosantaTransactionInfoConverter

    constructor(
        context: Context,
        words: List<String>,
        passphrase: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold
    ) : this(
        context,
        Mnemonic().toSeed(words, passphrase),
        walletId,
        networkType,
        peerSize,
        syncMode,
        confirmationsThreshold
    )

    constructor(
        context: Context,
        seed: ByteArray,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold
    ) : this(
        context = context,
        extendedKey = HDExtendedKey(seed, Purpose.BIP44),
        walletId = walletId,
        networkType = networkType,
        peerSize = peerSize,
        syncMode = syncMode,
        confirmationsThreshold = confirmationsThreshold
    )

    constructor(
        context: Context,
        watchAddress: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold
    ) : this(
        context = context,
        extendedKey = null,
        watchAddress = parseAddress(watchAddress, network(networkType)),
        walletId = walletId,
        networkType = networkType,
        peerSize = peerSize,
        syncMode = syncMode,
        confirmationsThreshold = confirmationsThreshold
    )

    constructor(
        context: Context,
        extendedKey: HDExtendedKey,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold
    ) : this(
        context = context,
        extendedKey = extendedKey,
        watchAddress = null,
        walletId = walletId,
        networkType = networkType,
        peerSize = peerSize,
        syncMode = syncMode,
        confirmationsThreshold = confirmationsThreshold
    )

    /**
     * @constructor Creates and initializes the BitcoinKit
     * @param context The Android context
     * @param extendedKey HDExtendedKey that contains HDKey and version
     * @param watchAddress address for watching in read-only mode
     * @param walletId an arbitrary ID of type String.
     * @param networkType The network type. The default is MainNet.
     * @param peerSize The # of peer-nodes required. The default is 10 peers.
     * @param syncMode How the kit syncs with the blockchain. The default is SyncMode.Api().
     * @param confirmationsThreshold How many confirmations required to be considered confirmed. The default is 6 confirmations.
     */
    private constructor(
        context: Context,
        extendedKey: HDExtendedKey?,
        watchAddress: Address?,
        walletId: String,
        networkType: NetworkType,
        peerSize: Int,
        syncMode: SyncMode,
        confirmationsThreshold: Int
    ) {
        val coreDatabase =
            CoreDatabase.getInstance(context, getDatabaseNameCore(networkType, walletId, syncMode))
        val cosantaDatabase = CosantaKitDatabase.getInstance(
            context,
            getDatabaseName(networkType, walletId, syncMode)
        )

        val coreStorage = Storage(coreDatabase)
        cosantaStorage = CosantaStorage(cosantaDatabase, coreStorage)

        network = network(networkType)

        val checkpoint = Checkpoint.resolveCheckpoint(syncMode, network, coreStorage)
        val apiSyncStateManager =
            ApiSyncStateManager(coreStorage, network.syncableFromApi && syncMode !is SyncMode.Full)

        val apiTransactionProvider =
            apiTransactionProvider(networkType, syncMode, apiSyncStateManager)

        val paymentAddressParser = PaymentAddressParser("cosanta", removeScheme = true)
        val instantTransactionManager = InstantTransactionManager(
            cosantaStorage,
            InstantSendFactory(),
            InstantTransactionState()
        )

        cosantaTransactionInfoConverter = CosantaTransactionInfoConverter(instantTransactionManager)

        val blockValidatorSet = BlockValidatorSet()
        blockValidatorSet.addBlockValidator(CosantaProofOfWorkValidator())
        blockValidatorSet.addBlockValidator(CosantaProofOfStakeValidator())

        val watchAddressPublicKey = watchAddress?.let {
            WatchAddressPublicKey(watchAddress.lockingScriptPayload, watchAddress.scriptType)
        }

        bitcoinCore = BitcoinCoreBuilder()
            .setContext(context)
            .setExtendedKey(extendedKey)
            .setWatchAddressPublicKey(watchAddressPublicKey)
            .setPurpose(Purpose.BIP44)
            .setNetwork(network)
            .setCheckpoint(checkpoint)
            .setPaymentAddressParser(paymentAddressParser)
            .setPeerSize(peerSize)
            .setSyncMode(syncMode)
            .setConfirmationThreshold(confirmationsThreshold)
            .setStorage(coreStorage)
            .setBlockHeaderHasher(X11HasherExt())
            .setApiTransactionProvider(apiTransactionProvider)
            .setApiSyncStateManager(apiSyncStateManager)
            .setTransactionInfoConverter(cosantaTransactionInfoConverter)
            .setBlockValidator(blockValidatorSet)
            .setCustomLastBlockProvider(CosantaLastBlockProvider(CosantaApi()))
            .build()
            .addMessageParser(CosantaCoinMerkleBlockMessage(CosantaBlockHeaderParser()))

        bitcoinCore.listener = this

        //  extending bitcoinCore

        bitcoinCore.addMessageParser(MasternodeListDiffMessageParser())
            .addMessageParser(TransactionLockMessageParser())
            .addMessageParser(TransactionLockVoteMessageParser())
            .addMessageParser(ISLockMessageParser())
            .addMessageParser(TransactionMessageParser())

        bitcoinCore.addMessageSerializer(GetMasternodeListDiffMessageSerializer())

        val merkleRootHasher = MerkleRootHasher()
        val merkleRootCreator = MerkleRootCreator(merkleRootHasher)
        val masternodeListMerkleRootCalculator =
            MasternodeListMerkleRootCalculator(merkleRootCreator)

        val quorumListManager = QuorumListManager(
            cosantaStorage,
            QuorumListMerkleRootCalculator(merkleRootCreator),
            QuorumSortedList()
        )
        val masternodeListManager = MasternodeListManager(
            cosantaStorage,
            masternodeListMerkleRootCalculator,
            MerkleBranch(),
            MasternodeSortedList(),
            quorumListManager
        )
        val masternodeSyncer = MasternodeListSyncer(
            bitcoinCore,
            PeerTaskFactory(),
            masternodeListManager,
            bitcoinCore.initialDownload
        )

        bitcoinCore.addPeerTaskHandler(masternodeSyncer)
        bitcoinCore.addPeerSyncListener(masternodeSyncer)
        bitcoinCore.addPeerGroupListener(masternodeSyncer)

        val base58AddressConverter =
            Base58AddressConverter(network.addressVersion, network.addressScriptVersion)
        bitcoinCore.addRestoreKeyConverter(Bip44RestoreKeyConverter(base58AddressConverter))

        val singleHasher = SingleSha256Hasher()
        val bls = BLS()
        val transactionLockVoteValidator =
            TransactionLockVoteValidator(cosantaStorage, singleHasher, bls)
        val instantSendLockValidator = InstantSendLockValidator(quorumListManager, bls)

        val transactionLockVoteManager = TransactionLockVoteManager(transactionLockVoteValidator)
        val instantSendLockManager = InstantSendLockManager(instantSendLockValidator)

        val instantSendLockHandler =
            InstantSendLockHandler(instantTransactionManager, instantSendLockManager)
        instantSendLockHandler.delegate = this
        val transactionLockVoteHandler =
            TransactionLockVoteHandler(instantTransactionManager, transactionLockVoteManager)
        transactionLockVoteHandler.delegate = this

        val instantSend = InstantSend(
            bitcoinCore.transactionSyncer,
            transactionLockVoteHandler,
            instantSendLockHandler
        )
        this.instantSend = instantSend

        bitcoinCore.addInventoryItemsHandler(instantSend)
        bitcoinCore.addPeerTaskHandler(instantSend)

        val calculator = TransactionSizeCalculator()
        val dustCalculator = DustCalculator(network.dustRelayTxFee, calculator)
        val confirmedUnspentOutputProvider =
            ConfirmedUnspentOutputProvider(coreStorage, confirmationsThreshold)
        bitcoinCore.prependUnspentOutputSelector(
            UnspentOutputSelector(
                calculator,
                dustCalculator,
                confirmedUnspentOutputProvider
            )
        )
    }

    private fun apiTransactionProvider(
        networkType: NetworkType,
        syncMode: SyncMode,
        apiSyncStateManager: ApiSyncStateManager
    ) = when (networkType) {
        NetworkType.MainNet -> {
            val cosantaApi = CosantaApi()

            if (syncMode is SyncMode.Blockchair) {
                val blockchairBlockHashFetcher = BlockchairBlockHashFetcher(cosantaApi)
                val blockchairProvider =
                    BlockchairTransactionProvider(cosantaApi, blockchairBlockHashFetcher)

                BiApiTransactionProvider(
                    restoreProvider = cosantaApi,
                    syncProvider = blockchairProvider,
                    syncStateManager = apiSyncStateManager
                )
            } else {
                cosantaApi
            }
        }

        NetworkType.TestNet -> {
            InsightApi("https://testnet-insight.dash.org/insight-api")
        }
    }

    // BitcoinCore.Listener
    override fun onTransactionsUpdate(
        inserted: List<TransactionInfo>,
        updated: List<TransactionInfo>
    ) {
        // check for all new transactions if it's has instant lock
        inserted.map { it.transactionHash.hexToByteArray().reversedArray() }.forEach {
            instantSend.handle(it)
        }

        listener?.onTransactionsUpdate(
            inserted.mapNotNull { it as? CosantaTransactionInfo },
            updated.mapNotNull { it as? CosantaTransactionInfo })
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

    override fun onKitStateUpdate(state: BitcoinCore.KitState) {
        listener?.onKitStateUpdate(state)
    }

    // IInstantTransactionDelegate
    override fun onUpdateInstant(transactionHash: ByteArray) {
        val transaction = cosantaStorage.getFullTransactionInfo(transactionHash) ?: return
        val transactionInfo = cosantaTransactionInfoConverter.transactionInfo(transaction)

        bitcoinCore.listenerExecutor.execute {
            listener?.onTransactionsUpdate(listOf(), listOf(transactionInfo))
        }
    }

    companion object {
        val defaultNetworkType: NetworkType = NetworkType.MainNet
        val defaultSyncMode: SyncMode = SyncMode.Api()
        const val defaultPeerSize: Int = 5
        const val defaultConfirmationsThreshold: Int = 6

        private fun getDatabaseNameCore(
            networkType: NetworkType,
            walletId: String,
            syncMode: SyncMode
        ) =
            "${getDatabaseName(networkType, walletId, syncMode)}-core"

        private fun getDatabaseName(
            networkType: NetworkType,
            walletId: String,
            syncMode: SyncMode
        ) =
            "Cosanta-${networkType.name}-$walletId-${syncMode.javaClass.simpleName}"

        private fun parseAddress(address: String, network: Network): Address {
            return Base58AddressConverter(
                network.addressVersion,
                network.addressScriptVersion
            ).convert(address)
        }

        private fun network(networkType: NetworkType) = when (networkType) {
            NetworkType.MainNet -> MainNetCosanta()
            NetworkType.TestNet -> TestNetCosanta()
        }

        fun clear(context: Context, networkType: NetworkType, walletId: String) {
            for (syncMode in listOf(SyncMode.Api(), SyncMode.Full(), SyncMode.Blockchair())) {
                try {
                    SQLiteDatabase.deleteDatabase(
                        context.getDatabasePath(
                            getDatabaseNameCore(
                                networkType,
                                walletId,
                                syncMode
                            )
                        )
                    )
                    SQLiteDatabase.deleteDatabase(
                        context.getDatabasePath(
                            getDatabaseName(
                                networkType,
                                walletId,
                                syncMode
                            )
                        )
                    )
                } catch (ex: Exception) {
                    continue
                }
            }
        }
    }

}
