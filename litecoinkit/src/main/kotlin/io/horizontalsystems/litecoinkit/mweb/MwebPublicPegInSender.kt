package io.horizontalsystems.litecoinkit.mweb

import android.content.Context
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressCodec
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClient
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.SecureRandom

private const val MWEB_PUBLIC_PEGIN_LOG_TAG = "MwebPublicPegIn"

/**
 * Send-only MWEB peg-in helper for public Litecoin wallets.
 *
 * The caller must decide whether the destination belongs to a local MWEB wallet
 * before using this path. Without a full MWEB engine, this sender cannot scan or
 * prove local ownership of the MWEB address.
 */
internal class MwebPublicPegInSender(
    context: Context,
    private val walletId: String,
    private val networkType: LitecoinKit.NetworkType,
    private val addressCodec: MwebAddressCodec,
    private val config: MwebPublicSendConfig,
) {
    private val context = context.applicationContext
    private val dataDir = MwebFiles.publicSendDaemonDataDir(this.context, networkType, walletId)
    private val random = SecureRandom()
    private val lock = Any()
    private val operationMutex = Mutex()
    private val key = Key(walletId, networkType)
    private var daemonClient: MwebDaemonClient? = null
    private var clientStarted = false
    private var registeredAsActive = false

    fun sendInfo(
        request: MwebSendRequest.PublicToMweb,
        publicOptions: MwebPublicSendOptions,
        publicTransactionBridge: MwebPublicTransactionBridge,
    ): MwebSendInfo {
        return runOnIoBlocking {
            operationMutex.withLock {
                resetClientOnDaemonCrash {
                    prepareTransaction(
                        request = request,
                        publicOptions = publicOptions,
                        publicTransactionBridge = publicTransactionBridge,
                        clientProvider = lazyClientProvider(),
                    ).sendInfo()
                }
            }
        }
    }

    suspend fun send(
        request: MwebSendRequest.PublicToMweb,
        publicOptions: MwebPublicSendOptions,
        publicTransactionBridge: MwebPublicTransactionBridge,
    ): MwebSendResult = withContext(config.dispatcherProvider.io) {
        operationMutex.withLock {
            resetClientOnDaemonCrashSuspend {
                Timber.tag(MWEB_PUBLIC_PEGIN_LOG_TAG).d("Public peg-in send started: feeRate=${request.feeRate}")
                val clientProvider = lazyClientProvider()
                val prepared = prepareTransaction(
                    request = request,
                    publicOptions = publicOptions,
                    publicTransactionBridge = publicTransactionBridge,
                    clientProvider = clientProvider,
                )
                Timber.tag(MWEB_PUBLIC_PEGIN_LOG_TAG).d(
                    "Public peg-in prepared: selectedPublicUtxos=${prepared.selectedPublicUtxos.size}, " +
                        "normalFee=${prepared.normalFee}, mwebFee=${prepared.mwebFee}, rawTemplateBytes=${prepared.rawTemplate.size}"
                )
                val activeClient = clientProvider()
                Timber.tag(MWEB_PUBLIC_PEGIN_LOG_TAG).d("Public peg-in daemon create started")
                val createResult = MwebDaemonErrorMapper.mapSuspend {
                    activeClient.create(prepared.rawTemplate, request.feeRate, dryRun = false)
                }
                Timber.tag(MWEB_PUBLIC_PEGIN_LOG_TAG).d(
                    "Public peg-in daemon create finished: rawBytes=${createResult.rawTransaction.size}, " +
                        "outputIds=${createResult.outputIds.size}"
                )
                val signedPublicTransaction = publicTransactionBridge.signPublicInputs(
                    rawTransaction = createResult.rawTransaction,
                    selectedPublicUtxos = prepared.selectedPublicUtxos,
                )
                Timber.tag(MWEB_PUBLIC_PEGIN_LOG_TAG).d(
                    "Public peg-in signed: tx=${signedPublicTransaction.publicTransaction?.header?.hash?.toReversedHex()}, " +
                        "inputs=${signedPublicTransaction.publicTransaction?.inputs?.size}, " +
                        "outputs=${signedPublicTransaction.publicTransaction?.outputs?.size}, " +
                        "rawBytes=${signedPublicTransaction.rawTransaction.size}"
                )
                Timber.tag(MWEB_PUBLIC_PEGIN_LOG_TAG).d("Public peg-in daemon broadcast started")
                val transactionHash = MwebDaemonErrorMapper.mapSuspend {
                    activeClient.broadcast(signedPublicTransaction.rawTransaction)
                }
                Timber.tag(MWEB_PUBLIC_PEGIN_LOG_TAG).d("Public peg-in daemon broadcast finished: tx=$transactionHash")
                signedPublicTransaction.publicTransaction?.let(publicTransactionBridge::processRelayed)
                MwebSendResult(
                    canonicalTransactionHash = transactionHash,
                    rawTransaction = signedPublicTransaction.rawTransaction,
                    outputIds = createResult.outputIds,
                )
            }
        }
    }

    private fun lazyClientProvider(): () -> MwebDaemonClient {
        var client: MwebDaemonClient? = null
        return { client ?: startedClient().also { client = it } }
    }

    fun stop() {
        runOnIoBlocking {
            operationMutex.withLock {
                stopClient()
            }
        }
    }

    private fun stopClient() {
        synchronized(lock) {
            try {
                daemonClient?.stop()
            } finally {
                dropClientState()
            }
        }
    }

    private fun prepareTransaction(
        request: MwebSendRequest.PublicToMweb,
        publicOptions: MwebPublicSendOptions,
        publicTransactionBridge: MwebPublicTransactionBridge,
        clientProvider: () -> MwebDaemonClient,
    ): PreparedMwebTransaction {
        val transactionPreparer = MwebTransactionPreparer(
            addressCodec = addressCodec,
            publicTransactionBridge = publicTransactionBridge,
            changeAddressProvider = { throw MwebError.NativeUnavailable() },
            syncStateProvider = { MwebSyncState(0, 0, 0) },
            utxosProvider = { emptyList() },
        )
        return transactionPreparer.prepare(request, publicOptions) { rawTemplate, feeRate ->
            MwebDaemonErrorMapper.map {
                clientProvider().create(rawTemplate, feeRate, dryRun = true).rawTransaction
            }
        }
    }

    private fun startedClient(): MwebDaemonClient {
        return MwebDaemonErrorMapper.map {
            synchronized(lock) {
                daemonClient?.let { client ->
                    if (!clientStarted) {
                        startClient(client)
                    }
                    return@synchronized client
                }

                val client = createClient()
                daemonClient = client
                startClient(client)
                registerActive()
                client
            }
        }
    }

    private fun startClient(client: MwebDaemonClient) {
        try {
            client.start()
            clientStarted = true
        } catch (error: Throwable) {
            daemonClient = null
            clientStarted = false
            try {
                client.stop()
            } catch (_: Throwable) { }
            throw error
        }
    }

    private fun createClient(): MwebDaemonClient {
        return MwebDaemonErrorMapper.map {
            dataDir.deleteRecursively()
            config.daemonClientFactory.create(
                MwebDaemonConfig(
                    networkType = networkType,
                    accountKeys = ephemeralAccountKeys(),
                    peerAddress = config.peerAddress,
                    dataDir = dataDir,
                    restoreHeight = 0,
                )
            )
        }
    }

    private fun <T> resetClientOnDaemonCrash(block: () -> T): T {
        return try {
            block()
        } catch (error: MwebError.DaemonCrashed) {
            dropClientAfterDaemonCrash()
            throw error
        }
    }

    private suspend fun <T> resetClientOnDaemonCrashSuspend(block: suspend () -> T): T {
        return try {
            block()
        } catch (error: MwebError.DaemonCrashed) {
            dropClientAfterDaemonCrash()
            throw error
        }
    }

    private fun dropClientAfterDaemonCrash() {
        synchronized(lock) {
            try {
                daemonClient?.stop()
            } catch (_: Throwable) { }
            dropClientState()
        }
    }

    private fun dropClientState() {
        daemonClient = null
        clientStarted = false
        unregisterActive()
        dataDir.deleteRecursively()
    }

    private fun ephemeralAccountKeys(): MwebAccountKeys {
        return MwebKeyManager(ByteArray(SEED_SIZE).also(random::nextBytes)).accountKeys()
    }

    private fun registerActive() {
        if (registeredAsActive) return
        registeredAsActive = true
        updateActiveSenders(key, delta = 1)
    }

    private fun unregisterActive() {
        if (!registeredAsActive) return
        registeredAsActive = false
        updateActiveSenders(key, delta = -1)
    }

    private fun <T> runOnIoBlocking(block: suspend () -> T): T {
        return runBlocking(config.dispatcherProvider.io) { block() }
    }

    companion object {
        private val registryLock = Any()
        private val activeSenders = mutableMapOf<Key, Int>()

        fun checkCanClear(walletId: String, networkType: LitecoinKit.NetworkType) {
            val activeCount = synchronized(registryLock) {
                activeSenders[Key(walletId, networkType)] ?: 0
            }
            check(activeCount == 0) {
                "Cannot clear active MWEB public-send daemon for $walletId ${networkType.name}; stop or dispose LitecoinKit first"
            }
        }

        private fun updateActiveSenders(key: Key, delta: Int) {
            synchronized(registryLock) {
                val count = (activeSenders[key] ?: 0) + delta
                if (count <= 0) {
                    activeSenders.remove(key)
                } else {
                    activeSenders[key] = count
                }
            }
        }

        private const val SEED_SIZE = 32
    }

    private data class Key(
        val walletId: String,
        val networkType: LitecoinKit.NetworkType,
    )
}

internal class MwebSignedPublicTransaction(
    val rawTransaction: ByteArray,
    val publicTransaction: FullTransaction?,
)

internal suspend fun MwebPublicTransactionBridge.signPublicInputs(
    rawTransaction: ByteArray,
    selectedPublicUtxos: List<UnspentOutput>,
): MwebSignedPublicTransaction {
    if (selectedPublicUtxos.isEmpty()) {
        return MwebSignedPublicTransaction(rawTransaction, publicTransaction = null)
    }
    val signedTransaction = sign(rawTransaction, selectedPublicUtxos)
    if (signedTransaction.inputs.isEmpty()) {
        Timber.tag(MWEB_PUBLIC_PEGIN_LOG_TAG).d(
            "Public peg-in signing produced transaction without inputs: " +
                "selectedPublicUtxos=${selectedPublicUtxos.size}, outputs=${signedTransaction.outputs.size}"
        )
        throw MwebError.SyncFailure(
            IllegalStateException("MWEB public peg-in transaction has no public inputs after signing")
        )
    }
    return MwebSignedPublicTransaction(serialize(signedTransaction), signedTransaction)
}
