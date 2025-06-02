package io.horizontalsystems.piratecashkit

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
import io.horizontalsystems.bitcoincore.transactions.builder.IInputSigner
import io.horizontalsystems.bitcoincore.transactions.builder.ISchnorrInputSigner
import io.horizontalsystems.bitcoincore.utils.Base58AddressConverter
import io.horizontalsystems.bitcoincore.utils.MerkleBranch
import io.horizontalsystems.bitcoincore.utils.PaymentAddressParser
import io.horizontalsystems.hdwalletkit.HDExtendedKey
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.piratecashkit.core.PirateCashTransactionInfoConverter
import io.horizontalsystems.piratecashkit.core.SingleSha256Hasher
import io.horizontalsystems.piratecashkit.instantsend.BLS
import io.horizontalsystems.piratecashkit.instantsend.InstantSendFactory
import io.horizontalsystems.piratecashkit.instantsend.InstantSendLockValidator
import io.horizontalsystems.piratecashkit.instantsend.InstantTransactionManager
import io.horizontalsystems.piratecashkit.instantsend.TransactionLockVoteValidator
import io.horizontalsystems.piratecashkit.instantsend.instantsendlock.InstantSendLockHandler
import io.horizontalsystems.piratecashkit.instantsend.instantsendlock.InstantSendLockManager
import io.horizontalsystems.piratecashkit.instantsend.transactionlockvote.TransactionLockVoteHandler
import io.horizontalsystems.piratecashkit.instantsend.transactionlockvote.TransactionLockVoteManager
import io.horizontalsystems.piratecashkit.managers.ConfirmedUnspentOutputProvider
import io.horizontalsystems.piratecashkit.managers.MasternodeListManager
import io.horizontalsystems.piratecashkit.managers.MasternodeListSyncer
import io.horizontalsystems.piratecashkit.managers.MasternodeSortedList
import io.horizontalsystems.piratecashkit.managers.QuorumListManager
import io.horizontalsystems.piratecashkit.managers.QuorumSortedList
import io.horizontalsystems.piratecashkit.masternodelist.MasternodeListMerkleRootCalculator
import io.horizontalsystems.piratecashkit.masternodelist.MerkleRootCreator
import io.horizontalsystems.piratecashkit.masternodelist.MerkleRootHasher
import io.horizontalsystems.piratecashkit.masternodelist.QuorumListMerkleRootCalculator
import io.horizontalsystems.piratecashkit.messages.PirateCashCoinMerkleBlockMessage
import io.horizontalsystems.piratecashkit.messages.GetMasternodeListDiffMessageSerializer
import io.horizontalsystems.piratecashkit.messages.ISLockMessageParser
import io.horizontalsystems.piratecashkit.messages.MasternodeListDiffMessageParser
import io.horizontalsystems.piratecashkit.messages.PirateCashBlockHeaderParser
import io.horizontalsystems.piratecashkit.messages.TransactionLockMessageParser
import io.horizontalsystems.piratecashkit.messages.TransactionLockVoteMessageParser
import io.horizontalsystems.piratecashkit.messages.TransactionMessageParser
import io.horizontalsystems.piratecashkit.models.InstantTransactionState
import io.horizontalsystems.piratecashkit.models.PirateCashTransactionInfo
import io.horizontalsystems.piratecashkit.serializer.PirateCashTransactionSerializer
import io.horizontalsystems.piratecashkit.storage.PirateCashKitDatabase
import io.horizontalsystems.piratecashkit.storage.PirateCashStorage
import io.horizontalsystems.piratecashkit.tasks.PeerTaskFactory
import io.horizontalsystems.piratecashkit.validators.PirateCashProofOfStakeValidator
import io.horizontalsystems.piratecashkit.validators.PirateCashProofOfWorkValidator

class PirateCashKit : AbstractKit, IInstantTransactionDelegate, BitcoinCore.Listener {
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

    private val pirateCashStorage: PirateCashStorage
    private val instantSend: InstantSend
    private val pirateCashTransactionInfoConverter: PirateCashTransactionInfoConverter

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
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        iInputSigner: IInputSigner? = null,
        iSchnorrInputSigner: ISchnorrInputSigner? = null
    ) : this(
        context = context,
        extendedKey = null,
        watchAddress = parseAddress(watchAddress, network(networkType)),
        walletId = walletId,
        networkType = networkType,
        peerSize = peerSize,
        syncMode = syncMode,
        confirmationsThreshold = confirmationsThreshold,
        iInputSigner = iInputSigner,
        iSchnorrInputSigner = iSchnorrInputSigner
    )

    constructor(
        context: Context,
        extendedKey: HDExtendedKey,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        iInputSigner: IInputSigner? = null,
        iSchnorrInputSigner: ISchnorrInputSigner? = null
    ) : this(
        context = context,
        extendedKey = extendedKey,
        watchAddress = null,
        walletId = walletId,
        networkType = networkType,
        peerSize = peerSize,
        syncMode = syncMode,
        confirmationsThreshold = confirmationsThreshold,
        iInputSigner = iInputSigner,
        iSchnorrInputSigner = iSchnorrInputSigner
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
        confirmationsThreshold: Int,
        iInputSigner: IInputSigner?,
        iSchnorrInputSigner: ISchnorrInputSigner?
    ) {
        val coreDatabase =
            CoreDatabase.getInstance(context, getDatabaseNameCore(networkType, walletId, syncMode))
        val pirateCashDatabase = PirateCashKitDatabase.getInstance(
            context,
            getDatabaseName(networkType, walletId, syncMode)
        )

        val transactionSerializer = PirateCashTransactionSerializer()
        val coreStorage = Storage(coreDatabase)
        pirateCashStorage = PirateCashStorage(pirateCashDatabase, coreStorage)

        network = network(networkType)

        val checkpoint = Checkpoint.resolveCheckpoint(syncMode, network, coreStorage)
        val apiSyncStateManager =
            ApiSyncStateManager(coreStorage, network.syncableFromApi && syncMode !is SyncMode.Full)

        val apiTransactionProvider =
            apiTransactionProvider(networkType, syncMode, apiSyncStateManager)

        val paymentAddressParser = PaymentAddressParser("piratecash", removeScheme = true)
        val instantTransactionManager = InstantTransactionManager(
            pirateCashStorage,
            InstantSendFactory(),
            InstantTransactionState()
        )

        pirateCashTransactionInfoConverter =
            PirateCashTransactionInfoConverter(instantTransactionManager)

        val blockValidatorSet = BlockValidatorSet()
        blockValidatorSet.addBlockValidator(PirateCashProofOfWorkValidator(ScryptHasher()))
        blockValidatorSet.addBlockValidator(PirateCashProofOfStakeValidator())

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
//            .setBlockHeaderHasher(Scry())
            .setApiTransactionProvider(apiTransactionProvider)
            .setApiSyncStateManager(apiSyncStateManager)
            .setTransactionInfoConverter(pirateCashTransactionInfoConverter)
            .setBlockValidator(blockValidatorSet)
            .setCustomLastBlockProvider(PirateCashLastBlockProvider(PirateCashApi()))
            .setRequestUnknownBlocks(true)
            .setTransactionSerializer(transactionSerializer)
            .apply {
                if(iInputSigner != null && iSchnorrInputSigner != null) {
                    setSigners(iInputSigner, iSchnorrInputSigner)
                }
            }
            .build()
            .addMessageParser(PirateCashCoinMerkleBlockMessage(PirateCashBlockHeaderParser()))

        bitcoinCore.listener = this

        //  extending bitcoinCore

        bitcoinCore.addMessageParser(MasternodeListDiffMessageParser())
            .addMessageParser(TransactionLockMessageParser(transactionSerializer))
            .addMessageParser(TransactionLockVoteMessageParser())
            .addMessageParser(ISLockMessageParser())
            .addMessageParser(TransactionMessageParser(transactionSerializer))

        bitcoinCore.addMessageSerializer(GetMasternodeListDiffMessageSerializer())

        val merkleRootHasher = MerkleRootHasher()
        val merkleRootCreator = MerkleRootCreator(merkleRootHasher)
        val masternodeListMerkleRootCalculator =
            MasternodeListMerkleRootCalculator(merkleRootCreator)

        val quorumListManager = QuorumListManager(
            pirateCashStorage,
            QuorumListMerkleRootCalculator(merkleRootCreator),
            QuorumSortedList()
        )
        val masternodeListManager = MasternodeListManager(
            pirateCashStorage,
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
            TransactionLockVoteValidator(pirateCashStorage, singleHasher, bls)
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
            val pirateCashApi = PirateCashApi()

            if (syncMode is SyncMode.Blockchair) {
                val blockchairBlockHashFetcher = BlockchairBlockHashFetcher(pirateCashApi)
                val blockchairProvider =
                    BlockchairTransactionProvider(pirateCashApi, blockchairBlockHashFetcher)

                BiApiTransactionProvider(
                    restoreProvider = pirateCashApi,
                    syncProvider = blockchairProvider,
                    syncStateManager = apiSyncStateManager
                )
            } else {
                pirateCashApi
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
            inserted.mapNotNull { it as? PirateCashTransactionInfo },
            updated.mapNotNull { it as? PirateCashTransactionInfo })
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
        val transaction = pirateCashStorage.getFullTransactionInfo(transactionHash) ?: return
        val transactionInfo = pirateCashTransactionInfoConverter.transactionInfo(transaction)

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
        ) = "PirateCash-${networkType.name}-$walletId-${syncMode.javaClass.simpleName}"

        private fun parseAddress(address: String, network: Network): Address {
            return Base58AddressConverter(
                network.addressVersion,
                network.addressScriptVersion
            ).convert(address)
        }

        private fun network(networkType: NetworkType) = when (networkType) {
            NetworkType.MainNet -> MainNetPirateCash()
            NetworkType.TestNet -> TestNetPirateCash()
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
