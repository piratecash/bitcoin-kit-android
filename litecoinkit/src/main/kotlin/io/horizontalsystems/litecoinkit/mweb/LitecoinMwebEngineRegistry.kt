package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.litecoinkit.LitecoinKit
import java.util.concurrent.ConcurrentHashMap

internal object LitecoinMwebEngineRegistry {
    private val entries = ConcurrentHashMap<Key, Entry>()

    fun acquire(
        dataDir: String,
        mwebDataDir: String,
        seed: ByteArray,
        databaseKey: ByteArray?,
        walletId: String,
        networkType: LitecoinKit.NetworkType,
        config: MwebConfig,
    ): LitecoinMwebEngineHandle {
        val key = Key(walletId, networkType)
        while (true) {
            val entry = entries[key] ?: Entry(
                dataDir = dataDir,
                mwebDataDir = mwebDataDir,
                seed = seed,
                databaseKey = databaseKey,
                walletId = walletId,
                networkType = networkType,
                config = config,
            ).let { candidate ->
                entries.putIfAbsent(key, candidate)?.also { candidate.dispose() } ?: candidate
            }

            if (!entry.acquire(config, databaseKey)) {
                entries.remove(key, entry)
                continue
            }

            return try {
                LitecoinMwebEngineHandle(key, entry.engine())
            } catch (error: Throwable) {
                if (entry.releaseReference()) {
                    entries.remove(key, entry)
                }
                throw error
            }
        }
    }

    fun clear(dataDir: String, mwebDataDir: String, walletId: String, networkType: LitecoinKit.NetworkType) {
        checkInactive(walletId, networkType)
        MwebFiles.clear(dataDir, mwebDataDir, networkType, walletId)
    }

    fun checkInactive(walletId: String, networkType: LitecoinKit.NetworkType) {
        check(entries[Key(walletId, networkType)] == null) {
            "Dispose all active LitecoinKit instances for $walletId ${networkType.name} first"
        }
    }

    internal fun start(key: Key) {
        entry(key).start()
    }

    internal fun stop(key: Key) {
        entry(key).stop()
    }

    internal fun release(key: Key) {
        val entry = entries[key] ?: return
        if (entry.releaseReference()) {
            entries.remove(key, entry)
            entry.dispose()
        }
    }

    internal data class Key(
        val walletId: String,
        val networkType: LitecoinKit.NetworkType,
    )

    private fun entry(key: Key): Entry {
        return checkNotNull(entries[key]) { "MWEB engine is not acquired for ${key.walletId} ${key.networkType.name}" }
    }

    /** Mutable lifecycle counters in this class are guarded by the entry monitor. */
    private class Entry(
        private val dataDir: String,
        private val mwebDataDir: String,
        seed: ByteArray,
        databaseKey: ByteArray?,
        walletId: String,
        private val networkType: LitecoinKit.NetworkType,
        config: MwebConfig,
        var references: Int = 0,
        var starts: Int = 0,
    ) {
        private val seed = seed.copyOf()
        private val databaseKey = databaseKey?.copyOf()
        private val restorePoint = config.restorePoint
        private val peerAddress = config.peerAddress
        private val dispatcherProvider = config.dispatcherProvider
        private val daemonClientFactory = config.daemonClientFactory
        private val walletId = walletId
        private var engine: LitecoinMwebEngine? = null
        private var disposed = false
        private var closing = false

        @Synchronized
        fun acquire(config: MwebConfig, databaseKey: ByteArray?): Boolean {
            if (disposed || closing) return false

            check(config.restorePoint == restorePoint && config.peerAddress == peerAddress) {
                "Conflicting MWEB config for $walletId ${networkType.name}"
            }
            check(this.databaseKey.contentEqualsNullable(databaseKey)) {
                "Conflicting MWEB database key for $walletId ${networkType.name}"
            }
            references += 1
            return true
        }

        @Synchronized
        fun engine(): LitecoinMwebEngine {
            check(!disposed) { "MWEB engine is disposed for $walletId ${networkType.name}" }
            engine?.let { return it }

            return LitecoinMwebEngine(
                dataDir = dataDir,
                mwebDataDir = mwebDataDir,
                seed = seed,
                databaseKey = databaseKey,
                walletId = walletId,
                dispatcherProvider = dispatcherProvider,
                networkType = networkType,
                restorePoint = restorePoint,
                peerAddress = peerAddress,
                daemonClientFactory = daemonClientFactory,
            ).also { engine = it }
        }

        /**
         * Serializes shared-engine startup for this wallet/network entry. Do not
         * invoke from dispatcherProvider.io: engine startup uses a blocking bridge
         * onto that dispatcher.
         */
        @Synchronized
        fun start() {
            check(!disposed) { "MWEB engine is disposed for $walletId ${networkType.name}" }
            starts += 1
            if (starts > 1) return

            try {
                engine().start()
            } catch (error: Throwable) {
                starts -= 1
                throw error
            }
        }

        /**
         * Serializes shared-engine shutdown for this wallet/network entry. Do not
         * invoke from dispatcherProvider.io: engine shutdown uses a blocking bridge
         * onto that dispatcher.
         */
        @Synchronized
        fun stop() {
            if (starts <= 0) return

            starts -= 1
            if (starts == 0) {
                engine?.stop()
            }
        }

        @Synchronized
        fun releaseReference(): Boolean {
            if (references <= 0) return false

            references -= 1
            if (references == 0) {
                closing = true
                return true
            }
            return false
        }

        @Synchronized
        fun dispose() {
            if (disposed) return

            disposed = true
            closing = true
            starts = 0
            engine?.dispose()
            engine = null
            seed.fill(0)
            databaseKey?.fill(0)
        }
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }
}

internal class LitecoinMwebEngineHandle(
    private val key: LitecoinMwebEngineRegistry.Key,
    engine: LitecoinMwebEngine,
) {
    private val engineRef = engine
    private var released = false
    private var started = false

    val engine: LitecoinMwebEngine
        @Synchronized
        get() {
            check(!released) { "MWEB engine handle is released" }
            return engineRef
        }

    /**
     * Synchronously starts the shared MWEB engine. Calls are serialized at the
     * shared-engine level and may block on same-wallet lifecycle work and native
     * startup.
     */
    @Synchronized
    fun start() {
        check(!released) { "MWEB engine handle is released" }
        if (started) return

        LitecoinMwebEngineRegistry.start(key)
        started = true
    }

    /**
     * Synchronously releases this handle's start reference. Calls are serialized at
     * the shared-engine level, and the shared engine is stopped only after the
     * last started handle stops.
     */
    @Synchronized
    fun stop() {
        if (!started) return

        LitecoinMwebEngineRegistry.stop(key)
        started = false
    }

    /** Releases this owner's engine reference. Safe to call more than once. */
    @Synchronized
    fun release() {
        if (released) return

        stop()
        LitecoinMwebEngineRegistry.release(key)
        released = true
    }
}
