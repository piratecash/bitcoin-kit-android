package io.horizontalsystems.bitcoincash

import io.horizontalsystems.bitcoincash.blocks.BitcoinCashBlockValidatorHelper
import io.horizontalsystems.bitcoincash.blocks.validators.AsertValidator
import io.horizontalsystems.bitcoincash.blocks.validators.DAAValidator
import io.horizontalsystems.bitcoincash.blocks.validators.EDAValidator
import io.horizontalsystems.bitcoincash.blocks.validators.ForkValidator
import io.horizontalsystems.bitcoincore.AbstractKit
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.bitcoincore.BitcoinCoreBuilder
import io.horizontalsystems.bitcoincore.apisync.BlockchainComApi
import io.horizontalsystems.bitcoincore.apisync.HsBlockHashFetcher
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairApi
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairBlockHashFetcher
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairTransactionProvider
import io.horizontalsystems.bitcoincore.blocks.BlockMedianTimeHelper
import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorChain
import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorSet
import io.horizontalsystems.bitcoincore.blocks.validators.LegacyDifficultyAdjustmentValidator
import io.horizontalsystems.bitcoincore.blocks.validators.ProofOfWorkValidator
import io.horizontalsystems.bitcoincore.core.IApiTransactionProvider
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.extensions.toReversedByteArray
import io.horizontalsystems.bitcoincore.managers.ApiSyncStateManager
import io.horizontalsystems.bitcoincore.managers.Bip44RestoreKeyConverter
import io.horizontalsystems.bitcoincore.managers.BlockchairCashRestoreKeyConverter
import io.horizontalsystems.bitcoincore.models.Address
import io.horizontalsystems.bitcoincore.models.Checkpoint
import io.horizontalsystems.bitcoincore.models.WatchAddressPublicKey
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.storage.CoreDatabase
import io.horizontalsystems.bitcoincore.storage.DatabaseEncryption
import io.horizontalsystems.bitcoincore.storage.DatabaseMigrationResult
import io.horizontalsystems.bitcoincore.storage.Storage
import io.horizontalsystems.bitcoincore.transactions.builder.IInputSigner
import io.horizontalsystems.bitcoincore.transactions.builder.ISchnorrInputSigner
import io.horizontalsystems.bitcoincore.utils.AddressConverterChain
import io.horizontalsystems.bitcoincore.utils.Base58AddressConverter
import io.horizontalsystems.bitcoincore.utils.CashAddressConverter
import io.horizontalsystems.bitcoincore.utils.PaymentAddressParser
import io.horizontalsystems.hdwalletkit.HDExtendedKey
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hdwalletkit.Mnemonic

class BitcoinCashKit : AbstractKit {
    sealed class NetworkType {
        class MainNet(val coinType: MainNetBitcoinCash.CoinType) : NetworkType()
        object TestNet : NetworkType()

        val description: String
            get() = when (this) {
                is MainNet -> {
                    when (coinType) {
                        MainNetBitcoinCash.CoinType.Type0 -> "mainNet" // back compatibility for database file name in old NetworkType
                        MainNetBitcoinCash.CoinType.Type145 -> "mainNet-145"
                    }
                }

                is TestNet -> "testNet"
            }
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
    ) : this(dataDir, connectionManager, Mnemonic().toSeed(words, passphrase), walletId, networkType, peerSize, syncMode, confirmationsThreshold)

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
    ) : this(dataDir, connectionManager, HDExtendedKey(seed, Purpose.BIP44), walletId, networkType, peerSize, syncMode, confirmationsThreshold)

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
            network = network,
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
        network: Network,
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
        val apiSyncStateManager = ApiSyncStateManager(storage, network.syncableFromApi && syncMode !is SyncMode.Full)
        val apiTransactionProvider = apiTransactionProvider(networkType, syncMode)
        val paymentAddressParser = PaymentAddressParser("bitcoincash", removeScheme = false)
        val blockValidatorSet = blockValidatorSet(networkType, storage)

        val bitcoinCore = BitcoinCoreBuilder()
            .setConnectionManager(connectionManager)
            .setExtendedKey(extendedKey)
            .setWatchAddressPublicKey(watchAddressPublicKey)
            .setPurpose(Purpose.BIP44)
            .setNetwork(network)
            .setCheckpoint(checkpoint)
            .setPaymentAddressParser(paymentAddressParser)
            .setPeerSize(peerSize)
            .setSyncMode(syncMode)
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

        //  extending bitcoinCore

        val bech32 = CashAddressConverter(network.addressSegwitHrp)
        bitcoinCore.prependAddressConverter(bech32)

        val restoreKeyConverter = if (syncMode is SyncMode.Blockchair) {
            BlockchairCashRestoreKeyConverter(bech32)
        } else {
            val base58 = Base58AddressConverter(network.addressVersion, network.addressScriptVersion)
            Bip44RestoreKeyConverter(base58)
        }
        bitcoinCore.addRestoreKeyConverter(restoreKeyConverter)

        return bitcoinCore
    }

    private fun parseAddress(address: String, network: Network): Address {
        val addressConverter = AddressConverterChain().apply {
            prependConverter(CashAddressConverter(network.addressSegwitHrp))
            prependConverter(Base58AddressConverter(network.addressVersion, network.addressScriptVersion))
        }
        return addressConverter.convert(address)
    }

    private fun blockValidatorSet(
        networkType: NetworkType,
        storage: Storage
    ): BlockValidatorSet {
        val blockValidatorSet = BlockValidatorSet()
        blockValidatorSet.addBlockValidator(ProofOfWorkValidator())

        val blockValidatorChain = BlockValidatorChain()
        if (networkType is NetworkType.MainNet) {
            val blockHelper = BitcoinCashBlockValidatorHelper(storage)

            val daaValidator = DAAValidator(targetSpacing, blockHelper)
            val asertValidator = AsertValidator()

            blockValidatorChain.add(ForkValidator(bchnChainForkHeight, bchnChainForkBlockHash, asertValidator))
            blockValidatorChain.add(asertValidator)

            blockValidatorChain.add(ForkValidator(svForkHeight, abcForkBlockHash, daaValidator))
            blockValidatorChain.add(daaValidator)

            blockValidatorChain.add(LegacyDifficultyAdjustmentValidator(blockHelper, heightInterval, targetTimespan, maxTargetBits))
            blockValidatorChain.add(EDAValidator(maxTargetBits, blockHelper, BlockMedianTimeHelper(storage)))
        }

        blockValidatorSet.addBlockValidator(blockValidatorChain)
        return blockValidatorSet
    }

    private fun apiTransactionProvider(
        networkType: NetworkType,
        syncMode: SyncMode
    ): IApiTransactionProvider {
        val blockchairApi = BlockchairApi(network.blockchairChainId, networkErrorHolder)
        val blockchairBlockHashFetcher = BlockchairBlockHashFetcher(blockchairApi)
        return when (networkType) {
            is NetworkType.MainNet -> {
                if (syncMode is SyncMode.Blockchair) {
                    BlockchairTransactionProvider(blockchairApi, blockchairBlockHashFetcher)
                } else {
                    BlockchainComApi("https://api.haskoin.com/bch/blockchain", blockchairBlockHashFetcher, networkErrorHolder)
                }
            }

            NetworkType.TestNet -> {
                BlockchainComApi(
                    transactionApiUrl = "https://api.haskoin.com/bchtest/blockchain",
                    blockHashFetcher = HsBlockHashFetcher("https://api.blocksdecoded.com/v1/blockchains/bitcoin-cash", networkErrorHolder),
                    networkErrorListener = networkErrorHolder
                )
            }
        }
    }

    private fun network(networkType: NetworkType) = when (networkType) {
        is NetworkType.MainNet -> MainNetBitcoinCash(networkType.coinType)
        NetworkType.TestNet -> TestNetBitcoinCash()
    }

    companion object {
        const val maxTargetBits: Long = 0x1d00ffff              // Maximum difficulty
        const val targetSpacing = 10 * 60                       // 10 minutes per block.
        const val targetTimespan: Long = 14 * 24 * 60 * 60      // 2 weeks per difficulty cycle, on average.
        var heightInterval = targetTimespan / targetSpacing     // 2016 blocks

        const val svForkHeight = 556767                         // 2018 November 14
        const val bchnChainForkHeight = 661648                  // 2020 November 15, 14:13 GMT

        val abcForkBlockHash = "0000000000000000004626ff6e3b936941d341c5932ece4357eeccac44e6d56c".toReversedByteArray()
        val bchnChainForkBlockHash = "0000000000000000029e471c41818d24b8b74c911071c4ef0b4a0509f9b5a8ce".toReversedByteArray()

        val defaultNetworkType: NetworkType = NetworkType.MainNet(MainNetBitcoinCash.CoinType.Type145)
        val defaultSyncMode: SyncMode = SyncMode.Api()
        const val defaultPeerSize: Int = 10
        const val defaultConfirmationsThreshold: Int = 6

        internal fun getDatabaseName(networkType: NetworkType, walletId: String, syncMode: SyncMode): String =
            "BitcoinCash-${networkType.description}-$walletId-${syncMode.javaClass.simpleName}"

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
            "bitcoincash-${networkType.description}-$walletId"

        private fun network(networkType: NetworkType) = when (networkType) {
            is NetworkType.MainNet -> MainNetBitcoinCash(networkType.coinType)
            NetworkType.TestNet -> TestNetBitcoinCash()
        }

        private fun addressConverter(network: Network): AddressConverterChain {
            val addressConverter = AddressConverterChain()

            val bech32 = CashAddressConverter(network.addressSegwitHrp)
            addressConverter.prependConverter(bech32)

            return addressConverter
        }

        fun firstAddress(
            seed: ByteArray,
            networkType: NetworkType,
        ): Address {
            return BitcoinCore.firstAddress(
                seed,
                Purpose.BIP44,
                network(networkType),
                addressConverter(network(networkType))
            )
        }

        fun firstAddress(
            extendedKey: HDExtendedKey,
            networkType: NetworkType,
        ): Address {
            return BitcoinCore.firstAddress(
                extendedKey,
                Purpose.BIP44,
                network(networkType),
                addressConverter(network(networkType))
            )
        }
    }
}
