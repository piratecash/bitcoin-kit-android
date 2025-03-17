package cash.p.dogecoinkit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import io.horizontalsystems.bitcoincore.storage.Storage
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
        context,
        HDExtendedKey(seed, Purpose.BIP44),
        walletId,
        networkType,
        peerSize,
        syncMode,
        confirmationsThreshold
    )

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
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold
    ) {
        network = network(networkType)

        bitcoinCore = bitcoinCore(
            context = context,
            extendedKey = extendedKey,
            watchAddressPublicKey = null,
            networkType = networkType,
            walletId = walletId,
            syncMode = syncMode,
            peerSize = peerSize,
            confirmationsThreshold = confirmationsThreshold
        )
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
        confirmationsThreshold: Int = defaultConfirmationsThreshold
    ) {
        network = network(networkType)

        val address = parseAddress(watchAddress, network)
        val watchAddressPublicKey =
            WatchAddressPublicKey(address.lockingScriptPayload, address.scriptType)

        bitcoinCore = bitcoinCore(
            context = context,
            extendedKey = null,
            watchAddressPublicKey = watchAddressPublicKey,
            networkType = networkType,
            walletId = walletId,
            syncMode = syncMode,
            peerSize = peerSize,
            confirmationsThreshold = confirmationsThreshold
        )
    }

    private fun bitcoinCore(
        context: Context,
        extendedKey: HDExtendedKey?,
        watchAddressPublicKey: WatchAddressPublicKey?,
        networkType: NetworkType,
        walletId: String,
        syncMode: SyncMode,
        peerSize: Int,
        confirmationsThreshold: Int
    ): BitcoinCore {
        val database =
            CoreDatabase.getInstance(context, getDatabaseName(networkType, walletId, syncMode))
        val storage = Storage(database)
        val checkpoint = Checkpoint.resolveCheckpoint(syncMode, network, storage)
        val apiSyncStateManager =
            ApiSyncStateManager(storage, network.syncableFromApi && syncMode !is SyncMode.Full)
        val blockchairApi = BlockchairApi(network.blockchairChainId)
        val apiTransactionProvider = apiTransactionProvider(networkType, blockchairApi)
        val paymentAddressParser = PaymentAddressParser("dogecoin", removeScheme = true)
        val blockValidatorSet = blockValidatorSet(storage, networkType)

        val coreBuilder = BitcoinCoreBuilder()
        val blockHeaderHasher = DoubleSha256Hasher()

        val bitcoinCore = coreBuilder
            .setContext(context)
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
            .setBlockValidator(blockValidatorSet)
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
/*
            blockValidatorChain.add(
                LegacyTestNetDifficultyValidator(
                    storage,
                    heightInterval,
                    targetSpacing,
                    maxTargetBits
                )
            )
*/
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
        const val targetTimespan: Long =
            302400         // 3.5 days per difficulty cycle, on average.
        const val heightInterval = targetTimespan / targetSpacing // 2016 blocks

        val defaultNetworkType: NetworkType = NetworkType.MainNet
        val defaultSyncMode: SyncMode = SyncMode.Api()
        const val defaultPeerSize: Int = 10
        const val defaultConfirmationsThreshold: Int = 6

        private fun getDatabaseName(
            networkType: NetworkType,
            walletId: String,
            syncMode: SyncMode
        ): String =
            "Dogecoin-${networkType.name}-$walletId-${syncMode.javaClass.simpleName}"

        fun clear(context: Context, networkType: NetworkType, walletId: String) {
            for (syncMode in listOf(SyncMode.Api(), SyncMode.Full(), SyncMode.Blockchair())) {
                for (purpose in Purpose.values())
                    try {
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
