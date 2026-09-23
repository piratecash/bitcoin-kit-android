package cash.p.dogecoinkit

import cash.p.dogecoinkit.messages.DogeCoinMerkleBlockMessageParser
import cash.p.dogecoinkit.validators.DogeDifficultyAdjustmentValidator
import cash.p.dogecoinkit.validators.DogeTestNetDifficultyAdjustmentValidator
import cash.p.dogecoinkit.validators.ProofOfWorkValidator
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
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.managers.ApiSyncStateManager
import io.horizontalsystems.bitcoincore.managers.Bip44RestoreKeyConverter
import io.horizontalsystems.bitcoincore.managers.BlockValidatorHelper
import io.horizontalsystems.bitcoincore.models.Address
import io.horizontalsystems.bitcoincore.models.Checkpoint
import io.horizontalsystems.bitcoincore.models.WatchAddressPublicKey
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.MerkleBlockMessageParser
import io.horizontalsystems.bitcoincore.serializers.BlockHeaderParser
import io.horizontalsystems.bitcoincore.storage.CoreDatabase
import io.horizontalsystems.bitcoincore.storage.DatabaseEncryption
import io.horizontalsystems.bitcoincore.storage.DatabaseMigrationResult
import io.horizontalsystems.bitcoincore.storage.Storage
import io.horizontalsystems.bitcoincore.transactions.builder.IInputSigner
import io.horizontalsystems.bitcoincore.transactions.builder.ISchnorrInputSigner
import io.horizontalsystems.bitcoincore.utils.AddressConverterChain
import io.horizontalsystems.bitcoincore.utils.Base58AddressConverter
import io.horizontalsystems.bitcoincore.utils.PaymentAddressParser
import io.horizontalsystems.bitcoincore.utils.SegwitAddressConverter
import io.horizontalsystems.hdwalletkit.HDExtendedKey
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hdwalletkit.Mnemonic

class DogecoinKit : AbstractKit {
    enum class NetworkType {
        MainNet,
        TestNet
    }

    interface Listener : BitcoinCore.Listener

    override var bitcoinCore: BitcoinCore
    override var network: Network

    var listener: Listener? = null
        set(value) {
            field = value
            bitcoinCore.listener = value
        }

    constructor(
        dataDir: String,
        connectionManager: IConnectionManager,
        words: List<String>,
        passphrase: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold
    ) : this(
        dataDir,
        connectionManager,
        Mnemonic().toSeed(words, passphrase),
        walletId,
        networkType,
        peerSize,
        syncMode,
        confirmationsThreshold
    )

    constructor(
        dataDir: String,
        databaseKey: ByteArray,
        connectionManager: IConnectionManager,
        words: List<String>,
        passphrase: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
    ) : this(
        dataDir = dataDir,
        databaseKey = databaseKey,
        connectionManager = connectionManager,
        seed = Mnemonic().toSeed(words, passphrase),
        walletId = walletId,
        networkType = networkType,
        peerSize = peerSize,
        syncMode = syncMode,
        confirmationsThreshold = confirmationsThreshold,
    )

    constructor(
        dataDir: String,
        connectionManager: IConnectionManager,
        seed: ByteArray,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold
    ) : this(
        dataDir,
        connectionManager,
        HDExtendedKey(seed, Purpose.BIP44),
        walletId,
        networkType,
        peerSize,
        syncMode,
        confirmationsThreshold
    )

    constructor(
        dataDir: String,
        databaseKey: ByteArray,
        connectionManager: IConnectionManager,
        seed: ByteArray,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
    ) : this(
        dataDir = dataDir,
        databaseKey = databaseKey,
        connectionManager = connectionManager,
        extendedKey = HDExtendedKey(seed, Purpose.BIP44),
        walletId = walletId,
        networkType = networkType,
        peerSize = peerSize,
        syncMode = syncMode,
        confirmationsThreshold = confirmationsThreshold,
    )

    /**
     * @constructor Creates and initializes the BitcoinKit
     * @param dataDir Absolute path of the app's `databases` directory
     *   (`context.getDatabasePath("x").parent`); any other directory opens an empty database.
     * @param connectionManager Source of network connectivity state.
     * @param extendedKey HDExtendedKey that contains HDKey and version
     * @param walletId Wallet ID; must not contain a path separator, it is embedded verbatim in the database file name.
     * @param networkType The network type. The default is MainNet.
     * @param peerSize The # of peer-nodes required. The default is 10 peers.
     * @param syncMode How the kit syncs with the blockchain. The default is SyncMode.Api().
     * @param confirmationsThreshold How many confirmations required to be considered confirmed. The default is 6 confirmations.
     */
    constructor(
        dataDir: String,
        connectionManager: IConnectionManager,
        extendedKey: HDExtendedKey,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        iInputSigner: IInputSigner? = null,
        iSchnorrInputSigner: ISchnorrInputSigner? = null
    ) : this(
        dataDir = dataDir,
        connectionManager = connectionManager,
        extendedKey = extendedKey,
        watchAddress = null,
        walletId = walletId,
        networkType = networkType,
        peerSize = peerSize,
        syncMode = syncMode,
        confirmationsThreshold = confirmationsThreshold,
        iInputSigner = iInputSigner,
        iSchnorrInputSigner = iSchnorrInputSigner,
        databaseKey = null,
    )

    /**
     * @constructor Creates and initializes the BitcoinKit
     * @param dataDir Absolute path of the app's `databases` directory
     *   (`context.getDatabasePath("x").parent`); any other directory opens an empty database.
     * @param databaseKey SQLCipher key of the wallet's databases.
     * @param connectionManager Source of network connectivity state.
     * @param extendedKey HDExtendedKey that contains HDKey and version
     * @param walletId Wallet ID; must not contain a path separator, it is embedded verbatim in the database file name.
     * @param networkType The network type. The default is MainNet.
     * @param peerSize The # of peer-nodes required. The default is 10 peers.
     * @param syncMode How the kit syncs with the blockchain. The default is SyncMode.Api().
     * @param confirmationsThreshold How many confirmations required to be considered confirmed. The default is 6 confirmations.
     */
    constructor(
        dataDir: String,
        databaseKey: ByteArray,
        connectionManager: IConnectionManager,
        extendedKey: HDExtendedKey,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        iInputSigner: IInputSigner? = null,
        iSchnorrInputSigner: ISchnorrInputSigner? = null,
    ) : this(
        dataDir = dataDir,
        connectionManager = connectionManager,
        extendedKey = extendedKey,
        watchAddress = null,
        walletId = walletId,
        networkType = networkType,
        peerSize = peerSize,
        syncMode = syncMode,
        confirmationsThreshold = confirmationsThreshold,
        iInputSigner = iInputSigner,
        iSchnorrInputSigner = iSchnorrInputSigner,
        databaseKey = databaseKey,
    )

    /**
     * @constructor Creates and initializes the BitcoinKit
     * @param dataDir Absolute path of the app's `databases` directory
     *   (`context.getDatabasePath("x").parent`); any other directory opens an empty database.
     * @param connectionManager Source of network connectivity state.
     * @param watchAddress address for watching in read-only mode
     * @param walletId Wallet ID; must not contain a path separator, it is embedded verbatim in the database file name.
     * @param networkType The network type. The default is MainNet.
     * @param peerSize The # of peer-nodes required. The default is 10 peers.
     * @param syncMode How the kit syncs with the blockchain. The default is SyncMode.Api().
     * @param confirmationsThreshold How many confirmations required to be considered confirmed. The default is 6 confirmations.
     */
    constructor(
        dataDir: String,
        connectionManager: IConnectionManager,
        watchAddress: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        iInputSigner: IInputSigner? = null,
        iSchnorrInputSigner: ISchnorrInputSigner? = null
    ) : this(
        dataDir = dataDir,
        connectionManager = connectionManager,
        extendedKey = null,
        watchAddress = watchAddress,
        walletId = walletId,
        networkType = networkType,
        peerSize = peerSize,
        syncMode = syncMode,
        confirmationsThreshold = confirmationsThreshold,
        iInputSigner = iInputSigner,
        iSchnorrInputSigner = iSchnorrInputSigner,
        databaseKey = null,
    )

    /**
     * @constructor Creates and initializes the BitcoinKit
     * @param dataDir Absolute path of the app's `databases` directory
     *   (`context.getDatabasePath("x").parent`); any other directory opens an empty database.
     * @param databaseKey SQLCipher key of the wallet's databases.
     * @param connectionManager Source of network connectivity state.
     * @param watchAddress address for watching in read-only mode
     * @param walletId Wallet ID; must not contain a path separator, it is embedded verbatim in the database file name.
     * @param networkType The network type. The default is MainNet.
     * @param peerSize The # of peer-nodes required. The default is 10 peers.
     * @param syncMode How the kit syncs with the blockchain. The default is SyncMode.Api().
     * @param confirmationsThreshold How many confirmations required to be considered confirmed. The default is 6 confirmations.
     */
    constructor(
        dataDir: String,
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
    ) : this(
        dataDir = dataDir,
        connectionManager = connectionManager,
        extendedKey = null,
        watchAddress = watchAddress,
        walletId = walletId,
        networkType = networkType,
        peerSize = peerSize,
        syncMode = syncMode,
        confirmationsThreshold = confirmationsThreshold,
        iInputSigner = iInputSigner,
        iSchnorrInputSigner = iSchnorrInputSigner,
        databaseKey = databaseKey,
    )

    private constructor(
        dataDir: String,
        connectionManager: IConnectionManager,
        extendedKey: HDExtendedKey?,
        watchAddress: String?,
        walletId: String,
        networkType: NetworkType,
        peerSize: Int,
        syncMode: SyncMode,
        confirmationsThreshold: Int,
        iInputSigner: IInputSigner?,
        iSchnorrInputSigner: ISchnorrInputSigner?,
        databaseKey: ByteArray?,
    ) {
        network = network(networkType)

        val watchAddressPublicKey = watchAddress?.let {
            val address = parseAddress(it, network)
            WatchAddressPublicKey(address.lockingScriptPayload, address.scriptType)
        }

        bitcoinCore = bitcoinCore(
            dataDir = dataDir,
            connectionManager = connectionManager,
            extendedKey = extendedKey,
            watchAddressPublicKey = watchAddressPublicKey,
            networkType = networkType,
            walletId = walletId,
            syncMode = syncMode,
            peerSize = peerSize,
            confirmationsThreshold = confirmationsThreshold,
            iInputSigner = iInputSigner,
            iSchnorrInputSigner = iSchnorrInputSigner,
            databaseKey = databaseKey,
        )
    }

    private fun bitcoinCore(
        dataDir: String,
        connectionManager: IConnectionManager,
        extendedKey: HDExtendedKey?,
        watchAddressPublicKey: WatchAddressPublicKey?,
        networkType: NetworkType,
        walletId: String,
        syncMode: SyncMode,
        peerSize: Int,
        confirmationsThreshold: Int,
        iInputSigner: IInputSigner?,
        iSchnorrInputSigner: ISchnorrInputSigner?,
        databaseKey: ByteArray?,
    ): BitcoinCore {
        val database =
            CoreDatabase.getInstance(dataDir, getDatabaseName(networkType, walletId, syncMode), databaseKey)
        val storage = Storage(database)
        val checkpoint = Checkpoint.resolveCheckpoint(syncMode, network, storage)
        val apiSyncStateManager =
            ApiSyncStateManager(storage, network.syncableFromApi && syncMode !is SyncMode.Full)
        val blockchairApi = BlockchairApi(network.blockchairChainId, networkErrorHolder)
        val apiTransactionProvider = apiTransactionProvider(networkType, blockchairApi)
        val paymentAddressParser = PaymentAddressParser("dogecoin", removeScheme = true)
        val blockValidatorSet = blockValidatorSet(storage, networkType)

        val coreBuilder = BitcoinCoreBuilder()
        val blockHeaderHasher = DoubleSha256Hasher()

        val bitcoinCore = coreBuilder
            .setConnectionManager(connectionManager)
            .setExtendedKey(extendedKey)
            .setWatchAddressPublicKey(watchAddressPublicKey)
            .setPurpose(Purpose.BIP44)
            .setNetwork(network)
            .setCheckpoint(checkpoint)
            .setPaymentAddressParser(paymentAddressParser)
            .setPeerSize(peerSize)
            .setSyncMode(syncMode)
            .setBlockHeaderHasher(blockHeaderHasher)
            .setSendType(BitcoinCore.SendType.API(blockchairApi))
            .setConfirmationThreshold(confirmationsThreshold)
            .setStorage(storage)
            .setApiTransactionProvider(apiTransactionProvider)
            .setApiSyncStateManager(apiSyncStateManager)
            .setNetworkErrorHolder(networkErrorHolder)
            .setBlockValidator(blockValidatorSet)
            .setAllowBroadcastFromUnsyncedPeers(true)
            .apply {
                if(iInputSigner != null && iSchnorrInputSigner != null) {
                    setSigners(iInputSigner, iSchnorrInputSigner)
                }
            }
            .build()
            // set message parser supports AuxPow
            .addMessageParser(DogeCoinMerkleBlockMessageParser(BlockHeaderParser(blockHeaderHasher)))

        //  extending bitcoinCore
        bitcoinCore.prependAddressConverter(
            Base58AddressConverter(
                network.addressVersion,
                network.addressScriptVersion
            )
        )
        bitcoinCore.addRestoreKeyConverter(
            Bip44RestoreKeyConverter(
                Base58AddressConverter(
                    network.addressVersion,
                    network.addressScriptVersion
                )
            )
        )
        return bitcoinCore
    }

    private fun parseAddress(address: String, network: Network): Address {
        val addressConverter = AddressConverterChain().apply {
            prependConverter(SegwitAddressConverter(network.addressSegwitHrp))
            prependConverter(
                Base58AddressConverter(
                    network.addressVersion,
                    network.addressScriptVersion
                )
            )
        }
        return addressConverter.convert(address)
    }

    private fun network(networkType: NetworkType) = when (networkType) {
        NetworkType.MainNet -> MainNetDogecoin()
        NetworkType.TestNet -> TestNetDogecoin()
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
            blockValidatorChain.add(
                DogeDifficultyAdjustmentValidator(
                    blockHelper,
                    maxTargetBits
                )
            )
            blockValidatorChain.add(BitsValidator())
        } else if (networkType == NetworkType.TestNet) {
            blockValidatorChain.add(
                DogeTestNetDifficultyAdjustmentValidator(
                    blockHelper,
                    maxTargetBits
                )
            )
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
        const val targetTimespan: Long =
            302400         // 3.5 days per difficulty cycle, on average.
        const val heightInterval = targetTimespan / targetSpacing // 2016 blocks

        val defaultNetworkType: NetworkType = NetworkType.MainNet
        val defaultSyncMode: SyncMode = SyncMode.Api()
        const val defaultPeerSize: Int = 10
        const val defaultConfirmationsThreshold: Int = 6

        internal fun getDatabaseName(
            networkType: NetworkType,
            walletId: String,
            syncMode: SyncMode
        ): String =
            "Dogecoin-${networkType.name}-$walletId-${syncMode.javaClass.simpleName}"

        internal fun databaseNames(networkType: NetworkType, walletId: String): List<String> =
            DatabaseEncryption.supportedSyncModes().map { syncMode ->
                getDatabaseName(networkType, walletId, syncMode)
            }

        /** Must be called before constructing any kit for this wallet. */
        suspend fun migrateDatabases(
            dataDir: String,
            networkType: NetworkType,
            walletId: String,
            databaseKey: ByteArray,
        ): DatabaseMigrationResult = DatabaseEncryption.migrateDatabases(
            dataDir = dataDir,
            databaseNames = databaseNames(networkType, walletId),
            migrationId = migrationId(networkType, walletId),
            databaseKey = databaseKey,
        )

        fun clear(dataDir: String, networkType: NetworkType, walletId: String) {
            DatabaseEncryption.clearDatabases(
                dataDir = dataDir,
                databaseNames = databaseNames(networkType, walletId),
                migrationId = migrationId(networkType, walletId),
            )
        }

        private fun migrationId(networkType: NetworkType, walletId: String): String =
            "dogecoin-${networkType.name}-$walletId"
    }

}
