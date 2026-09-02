package io.horizontalsystems.bitcoinkit

import io.horizontalsystems.bitcoincore.AbstractKit
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.bitcoincore.BitcoinCoreBuilder
import io.horizontalsystems.bitcoincore.apisync.BCoinApi
import io.horizontalsystems.bitcoincore.apisync.BlockHashFetcher
import io.horizontalsystems.bitcoincore.apisync.BlockchainComApi
import io.horizontalsystems.bitcoincore.apisync.HsBlockHashFetcher
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairApi
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairBlockHashFetcher
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairTransactionProvider
import io.horizontalsystems.bitcoincore.blocks.BlockMedianTimeHelper
import io.horizontalsystems.bitcoincore.blocks.validators.*
import io.horizontalsystems.bitcoincore.core.DoubleSha256Hasher
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.purpose
import io.horizontalsystems.bitcoincore.managers.*
import io.horizontalsystems.bitcoincore.models.Address
import io.horizontalsystems.bitcoincore.models.Checkpoint
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
import io.horizontalsystems.bitcoincore.storage.DatabaseEncryption
import io.horizontalsystems.bitcoincore.storage.DatabaseMigrationResult
import io.horizontalsystems.bitcoincore.storage.Storage
import io.horizontalsystems.bitcoincore.transactions.builder.IInputSigner
import io.horizontalsystems.bitcoincore.transactions.builder.ISchnorrInputSigner
import io.horizontalsystems.bitcoincore.utils.AddressConverterChain
import io.horizontalsystems.bitcoincore.utils.Base58AddressConverter
import io.horizontalsystems.bitcoincore.utils.IAddressConverter
import io.horizontalsystems.bitcoincore.utils.PaymentAddressParser
import io.horizontalsystems.bitcoincore.utils.SegwitAddressConverter
import io.horizontalsystems.hdwalletkit.*
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hodler.HodlerPlugin
import java.util.concurrent.ConcurrentHashMap

/**
 *
 *
 * The kit that connects to the Bitcoin Network and creates the Bitcoin wallet.
 * Extends from the AbstractKit class.
 * @property NetworkType The enum class type that determines which bitcoin network the kit is connects to. (MainNet, TestNet, or RegTest)
 * @property Listener Interface of BitcoinCore.Listener
 * @property bitcoinCore Reference to the BitcoinCore class.
 * @property network  The type of network that this kit is connected to. It is determined by the NetWorkType enum class.
 * @property listener Changeable variable of BitcoinCore.Listener.
 *
 */
class BitcoinKit : AbstractKit {

    enum class NetworkType {
        MainNet, TestNet, RegTest
    }

    interface Listener : BitcoinCore.Listener

    override var bitcoinCore: BitcoinCore
    override var network: Network

    var listener: Listener? = null
        set(value) {
            field = value
            bitcoinCore.listener = value
        }

    /**
     * @constructor Creates and initializes the BitcoinKit
     * @param dataDir Absolute path of the app's `databases` directory
     *   (`context.getDatabasePath("x").parent`); any other directory opens an empty database.
     * @param connectionManager Source of network connectivity state.
     * @param words A list of words of type String.
     * @param passphrase The passphrase to the wallet.
     * @param walletId an arbitrary ID of type String.
     * @param networkType The network type. The default is MainNet
     * @param peerSize The # of peer-nodes required. The default is 10 peers.
     * @param minConnectedPeerSize The minimum # of connected peers required to broadcast. Default is 2 peers.
     * @param syncMode How the kit syncs with the blockchain. Default is SyncMode.Api().
     * @param confirmationsThreshold How many confirmations required to be considered confirmed. Default is 6 confirmations.
     * @param purpose which BIP algorithm to use for wallet generation. Default is BIP44.
     */
    constructor(
        dataDir: String,
        connectionManager: IConnectionManager,
        words: List<String>,
        passphrase: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        minConnectedPeerSize: Int = defaultMinConnectedPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        purpose: Purpose = Purpose.BIP44,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null
    ) : this(dataDir, connectionManager, Mnemonic().toSeed(words, passphrase), walletId, networkType, peerSize, minConnectedPeerSize, syncMode, confirmationsThreshold, purpose, sharedPeerGroupHolder = sharedPeerGroupHolder)

    constructor(
        dataDir: String,
        databaseKey: ByteArray,
        connectionManager: IConnectionManager,
        words: List<String>,
        passphrase: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        minConnectedPeerSize: Int = defaultMinConnectedPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        purpose: Purpose = Purpose.BIP44,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null,
    ) : this(
        dataDir, databaseKey, connectionManager, Mnemonic().toSeed(words, passphrase), walletId, networkType,
        peerSize, minConnectedPeerSize, syncMode, confirmationsThreshold, purpose, sharedPeerGroupHolder,
    )


    /**
     * @constructor Creates and initializes the BitcoinKit
     * @param dataDir Absolute path of the app's `databases` directory
     *   (`context.getDatabasePath("x").parent`); any other directory opens an empty database.
     * @param connectionManager Source of network connectivity state.
     * @param seed A byte array that contains the seed.
     * @param walletId an arbitrary ID of type String.
     * @param networkType The network type. The default is MainNet
     * @param peerSize The # of peer-nodes required. The default is 10 peers.
     * @param minConnectedPeerSize The minimum # of connected peers required to broadcast. Default is 2 peers.
     * @param syncMode How the kit syncs with the blockchain. Default is SyncMode.Api().
     * @param confirmationsThreshold How many confirmations required to be considered confirmed. Default is 6 confirmations.
     * @param purpose which BIP algorithm to use for wallet generation. Default is BIP44.
     */
    constructor(
        dataDir: String,
        connectionManager: IConnectionManager,
        seed: ByteArray,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        minConnectedPeerSize: Int = defaultMinConnectedPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        purpose: Purpose = Purpose.BIP44,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null
    ) : this(dataDir, connectionManager, HDExtendedKey(seed, purpose), purpose, walletId, networkType, peerSize, minConnectedPeerSize, syncMode, confirmationsThreshold, null, null, sharedPeerGroupHolder)

    constructor(
        dataDir: String,
        databaseKey: ByteArray,
        connectionManager: IConnectionManager,
        seed: ByteArray,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        minConnectedPeerSize: Int = defaultMinConnectedPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        purpose: Purpose = Purpose.BIP44,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null,
    ) : this(
        dataDir, connectionManager, HDExtendedKey(seed, purpose), purpose, null, walletId, networkType,
        peerSize, minConnectedPeerSize, syncMode, confirmationsThreshold, null, null, sharedPeerGroupHolder, databaseKey,
    )

    /**
     * @constructor Creates and initializes the BitcoinKit
     * @param dataDir Absolute path of the app's `databases` directory
     *   (`context.getDatabasePath("x").parent`); any other directory opens an empty database.
     * @param connectionManager Source of network connectivity state.
     * @param extendedKey HDExtendedKey that contains HDKey and version
     * @param purpose Used for HDKey derivation
     * @param walletId an arbitrary ID of type String.
     * @param networkType The network type. The default is MainNet.
     * @param peerSize The # of peer-nodes required. The default is 10 peers.
     * @param minConnectedPeerSize The minimum # of connected peers required to broadcast. Default is 2 peers.
     * @param syncMode How the kit syncs with the blockchain. The default is SyncMode.Api().
     * @param confirmationsThreshold How many confirmations required to be considered confirmed. The default is 6 confirmations.
     * @param iInputSigner Optional input signer for transaction signing.
     * @param iSchnorrInputSigner Optional Schnorr input signer for transaction signing.
     */
    constructor(
        dataDir: String,
        connectionManager: IConnectionManager,
        extendedKey: HDExtendedKey,
        purpose: Purpose,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        minConnectedPeerSize: Int = defaultMinConnectedPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        iInputSigner: IInputSigner? = null,
        iSchnorrInputSigner: ISchnorrInputSigner? = null,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null
    ) : this(
        dataDir, connectionManager, extendedKey, purpose, null, walletId, networkType, peerSize,
        minConnectedPeerSize, syncMode, confirmationsThreshold, iInputSigner, iSchnorrInputSigner,
        sharedPeerGroupHolder, null,
    )

    constructor(
        dataDir: String,
        databaseKey: ByteArray,
        connectionManager: IConnectionManager,
        extendedKey: HDExtendedKey,
        purpose: Purpose,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        minConnectedPeerSize: Int = defaultMinConnectedPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        iInputSigner: IInputSigner? = null,
        iSchnorrInputSigner: ISchnorrInputSigner? = null,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null,
    ) : this(
        dataDir, connectionManager, extendedKey, purpose, null, walletId, networkType, peerSize,
        minConnectedPeerSize, syncMode, confirmationsThreshold, iInputSigner, iSchnorrInputSigner,
        sharedPeerGroupHolder, databaseKey,
    )

    /**
     * @constructor Creates and initializes the BitcoinKit
     * @param dataDir Absolute path of the app's `databases` directory
     *   (`context.getDatabasePath("x").parent`); any other directory opens an empty database.
     * @param connectionManager Source of network connectivity state.
     * @param watchAddress address for watching in read-only mode
     * @param walletId an arbitrary ID of type String.
     * @param networkType The network type. The default is MainNet.
     * @param peerSize The # of peer-nodes required. The default is 10 peers.
     * @param minConnectedPeerSize The minimum # of connected peers required to broadcast. Default is 2 peers.
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
        minConnectedPeerSize: Int = defaultMinConnectedPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null
    ) : this(
        dataDir, connectionManager, null, null, watchAddress, walletId, networkType, peerSize,
        minConnectedPeerSize, syncMode, confirmationsThreshold, null, null, sharedPeerGroupHolder, null,
    )

    constructor(
        dataDir: String,
        databaseKey: ByteArray,
        connectionManager: IConnectionManager,
        watchAddress: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        minConnectedPeerSize: Int = defaultMinConnectedPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        sharedPeerGroupHolder: SharedPeerGroupHolder? = null,
    ) : this(
        dataDir, connectionManager, null, null, watchAddress, walletId, networkType, peerSize,
        minConnectedPeerSize, syncMode, confirmationsThreshold, null, null, sharedPeerGroupHolder, databaseKey,
    )

    private constructor(
        dataDir: String,
        connectionManager: IConnectionManager,
        extendedKey: HDExtendedKey?,
        purpose: Purpose?,
        watchAddress: String?,
        walletId: String,
        networkType: NetworkType,
        peerSize: Int,
        minConnectedPeerSize: Int,
        syncMode: SyncMode,
        confirmationsThreshold: Int,
        iInputSigner: IInputSigner?,
        iSchnorrInputSigner: ISchnorrInputSigner?,
        sharedPeerGroupHolder: SharedPeerGroupHolder?,
        databaseKey: ByteArray?,
    ) {
        network = network(networkType)

        val address = watchAddress?.let { parseAddress(it, network) }
        val watchAddressPublicKey = address?.let { WatchAddressPublicKey(it.lockingScriptPayload, it.scriptType) }
        val resolvedPurpose = purpose ?: address?.scriptType?.purpose
            ?: throw IllegalStateException("Wallet purpose is unavailable")

        bitcoinCore = bitcoinCore(
            dataDir = dataDir,
            connectionManager = connectionManager,
            extendedKey = extendedKey,
            watchAddressPublicKey = watchAddressPublicKey,
            purpose = resolvedPurpose,
            networkType = networkType,
            network = network,
            walletId = walletId,
            syncMode = syncMode,
            peerSize = peerSize,
            minConnectedPeerSize = minConnectedPeerSize,
            confirmationsThreshold = confirmationsThreshold,
            iInputSigner = iInputSigner,
            iSchnorrInputSigner = iSchnorrInputSigner,
            sharedPeerGroupHolder = sharedPeerGroupHolder,
            databaseKey = databaseKey,
        )
    }

    private fun bitcoinCore(
        dataDir: String,
        connectionManager: IConnectionManager,
        extendedKey: HDExtendedKey?,
        watchAddressPublicKey: WatchAddressPublicKey?,
        purpose: Purpose,
        networkType: NetworkType,
        network: Network,
        walletId: String,
        syncMode: SyncMode,
        peerSize: Int,
        minConnectedPeerSize: Int,
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
        val apiTransactionProvider = apiTransactionProvider(networkType, syncMode, checkpoint)
        val paymentAddressParser = PaymentAddressParser("bitcoin", removeScheme = true)
        val blockValidatorSet = blockValidatorSet(networkType, storage)

        val coreBuilder = BitcoinCoreBuilder()
        val hodlerPlugin = hodlerPlugin(storage, syncMode, coreBuilder.addressConverter)

        val bitcoinCore = coreBuilder
            .setConnectionManager(connectionManager)
            .setExtendedKey(extendedKey)
            .setWatchAddressPublicKey(watchAddressPublicKey)
            .setPurpose(purpose)
            .setNetwork(network)
            .setCheckpoint(checkpoint)
            .setPaymentAddressParser(paymentAddressParser)
            .setMinConnectedPeerSize(minConnectedPeerSize)
            .setPeerSize(peerSize)
            .setSyncMode(syncMode)
            .setConfirmationThreshold(confirmationsThreshold)
            .setStorage(storage)
            .setApiTransactionProvider(apiTransactionProvider)
            .setApiSyncStateManager(apiSyncStateManager)
            .setNetworkErrorHolder(networkErrorHolder)
            .setBlockValidator(blockValidatorSet)
            .setHandleAddrMessage(false)
            .setAllowBroadcastFromUnsyncedPeers(true)
            .addPlugin(hodlerPlugin)
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
                bitcoinCore.addRestoreKeyConverter(hodlerPlugin)
            }

            Purpose.BIP49 -> {
                bitcoinCore.addRestoreKeyConverter(Bip49RestoreKeyConverter(base58AddressConverter))
            }

            Purpose.BIP84 -> {
                bitcoinCore.addRestoreKeyConverter(Bip84RestoreKeyConverter(bech32AddressConverter))
            }

            Purpose.BIP86 -> {
                bitcoinCore.addRestoreKeyConverter(Bip86RestoreKeyConverter(bech32AddressConverter))
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

    private fun hodlerPlugin(
        storage: Storage,
        syncMode: SyncMode,
        addressConverter: IAddressConverter
    ): HodlerPlugin {
        val blockMedianTimeHelper = BlockMedianTimeHelper(storage, approximate = syncMode is SyncMode.Blockchair)
        return HodlerPlugin(addressConverter, storage, blockMedianTimeHelper)
    }

    private fun blockValidatorSet(
        networkType: NetworkType,
        storage: Storage
    ): BlockValidatorSet {
        val blockHelper = BlockValidatorHelper(storage)
        val blockValidatorSet = BlockValidatorSet()

        blockValidatorSet.addBlockValidator(ProofOfWorkValidator())

        val blockValidatorChain = BlockValidatorChain()
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
        syncMode: SyncMode,
        checkpoint: Checkpoint
    ) = when (networkType) {
        NetworkType.MainNet -> {
            val hsBlockHashFetcher = HsBlockHashFetcher("https://api.blocksdecoded.com/v1/blockchains/bitcoin", networkErrorHolder)
            if (syncMode is SyncMode.Blockchair) {
                val blockchairApi = BlockchairApi(network.blockchairChainId, networkErrorHolder)
                val blockchairBlockHashFetcher = BlockchairBlockHashFetcher(blockchairApi)
                val blockHashFetcher = BlockHashFetcher(hsBlockHashFetcher, blockchairBlockHashFetcher, checkpoint.block.height)
                val blockchairProvider = BlockchairTransactionProvider(blockchairApi, blockHashFetcher)
                blockchairProvider
            } else {
                BlockchainComApi("https://blockchain.info", hsBlockHashFetcher, networkErrorHolder)
            }
        }

        NetworkType.TestNet -> {
            BCoinApi("https://btc-testnet.blocksdecoded.com/api", networkErrorHolder)
        }

        NetworkType.RegTest -> {
            null
        }
    }

    companion object {
        const val maxTargetBits: Long = 0x1d00ffff                // Maximum difficulty
        const val targetSpacing = 10 * 60                         // 10 minutes per block.
        const val targetTimespan: Long = 14 * 24 * 60 * 60        // 2 weeks per difficulty cycle, on average.
        const val heightInterval = targetTimespan / targetSpacing // 2016 blocks

        val defaultNetworkType: NetworkType = NetworkType.MainNet
        val defaultSyncMode: SyncMode = SyncMode.Api()
        const val defaultPeerSize: Int = 10
        const val defaultMinConnectedPeerSize: Int = 2
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
                handleAddrMessage = false
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

        /**
         * Gets the name of the BitcoinKit database
         * @param networkType The network type (MAIN, TEST, or REG)
         * @param walletId The walletID
         * @param syncMode The SyncMode
         * @param bip The BIP
         * @return database name
         */

        internal fun getDatabaseName(networkType: NetworkType, walletId: String, syncMode: SyncMode, purpose: Purpose): String =
            "Bitcoin-${networkType.name}-$walletId-${syncMode.javaClass.simpleName}-${purpose.name}"

        internal fun sharedDbName(networkType: NetworkType, walletId: String): String =
            "Bitcoin-Shared-${networkType.name}-$walletId"

        internal fun databaseNames(networkType: NetworkType, walletId: String): List<String> = buildList {
            add(sharedDbName(networkType, walletId))
            DatabaseEncryption.supportedSyncModes().forEach { syncMode ->
                Purpose.values().forEach { purpose -> add(getDatabaseName(networkType, walletId, syncMode, purpose)) }
            }
        }

        /** Must be called before constructing any kit for this wallet. */
        suspend fun migrateDatabases(
            dataDir: String,
            networkType: NetworkType,
            walletId: String,
            databaseKey: ByteArray,
        ): DatabaseMigrationResult {
            check(!sharedGroups.containsKey(migrationId(networkType, walletId))) {
                "Dispose the active BitcoinKit instances before migrating their databases"
            }
            return DatabaseEncryption.migrateDatabases(
                dataDir = dataDir,
                databaseNames = databaseNames(networkType, walletId),
                migrationId = migrationId(networkType, walletId),
                databaseKey = databaseKey,
            )
        }

        /**
         * Clears the database
         * @param dataDir Absolute path of the app's `databases` directory
         *   (`context.getDatabasePath("x").parent`); any other directory opens an empty database.
         * @param networkType The networkType of the BitcoinKit.
         * @param walletId The string wallet ID of the BitcoinKit.
         */
        fun clear(dataDir: String, networkType: NetworkType, walletId: String) {
            releaseSharedPeerGroup(walletId, networkType)
            DatabaseEncryption.clearDatabases(
                dataDir = dataDir,
                databaseNames = databaseNames(networkType, walletId),
                migrationId = migrationId(networkType, walletId),
            )
        }

        private fun migrationId(networkType: NetworkType, walletId: String): String =
            "bitcoin-${networkType.name}-$walletId"

        private fun network(networkType: NetworkType) = when (networkType) {
            NetworkType.MainNet -> MainNet()
            NetworkType.TestNet -> TestNet()
            NetworkType.RegTest -> RegTest()
        }

        private fun addressConverter(purpose: Purpose, network: Network): AddressConverterChain {
            val addressConverter = AddressConverterChain()
            when (purpose) {
                Purpose.BIP44,
                Purpose.BIP49,
                    -> {
                    addressConverter.prependConverter(Base58AddressConverter(network.addressVersion, network.addressScriptVersion))
                }
                Purpose.BIP84,
                Purpose.BIP86,
                    -> {
                    addressConverter.prependConverter(SegwitAddressConverter(network.addressSegwitHrp))
                }
            }

            return addressConverter
        }

        fun firstAddress(
            seed: ByteArray,
            purpose: Purpose,
            networkType: NetworkType,
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
            networkType: NetworkType,
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
