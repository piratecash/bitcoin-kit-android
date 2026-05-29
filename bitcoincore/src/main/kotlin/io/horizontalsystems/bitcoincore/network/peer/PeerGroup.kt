package io.horizontalsystems.bitcoincore.network.peer

import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IPeerAddressManager
import io.horizontalsystems.bitcoincore.core.IPeerAddressManagerListener
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.AddrMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.InvMessage
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import io.horizontalsystems.bitcoincore.network.messages.RejectMessage
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import timber.log.Timber
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

open class PeerGroup(
    private val hostManager: IPeerAddressManager,
    private val network: Network,
    private val peerManager: PeerManager,
    peerSize: Int,
    private val networkMessageParser: NetworkMessageParser,
    private val networkMessageSerializer: NetworkMessageSerializer,
    private val connectionManager: IConnectionManager,
    private val localDownloadedBestBlockHeight: Int,
    private val handleAddrMessage: Boolean
) : Peer.Listener, IPeerAddressManagerListener {

    interface Listener {
        fun onStart() = Unit
        fun onStop() = Unit
        fun onRefresh() = Unit
        fun onPeerCreate(peer: Peer) = Unit
        fun onPeerConnect(peer: Peer) = Unit
        fun onPeerDisconnect(peer: Peer, e: Exception?) = Unit
        fun onPeerReady(peer: Peer) = Unit
        fun onPeerReject(peer: Peer, rejectMessage: RejectMessage) = Unit
    }

    private val inventoryItemsHandlers = CopyOnWriteArrayList<IInventoryItemsHandler>()
    private val peerTaskHandlers = CopyOnWriteArrayList<IPeerTaskHandler>()
    private val getAddrRequestedHosts = ConcurrentHashMap.newKeySet<String>()
    private val acceptedPeerHosts = ConcurrentHashMap.newKeySet<String>()

    fun addInventoryItemsHandler(handler: IInventoryItemsHandler) {
        inventoryItemsHandlers.add(handler)
    }

    fun removeInventoryItemsHandler(handler: IInventoryItemsHandler) {
        inventoryItemsHandlers.remove(handler)
    }

    fun addPeerTaskHandler(handler: IPeerTaskHandler) {
        peerTaskHandlers.add(handler)
    }

    fun removePeerTaskHandler(handler: IPeerTaskHandler) {
        peerTaskHandlers.remove(handler)
    }

    @Volatile
    var running = false
        private set

    private val peerGroupListeners = CopyOnWriteArrayList<Listener>()
    // Pools are recreated on every start() after a stop() — once shut down an
    // ExecutorService cannot be reused. This makes the lifecycle:
    //   create → start → stop (shutdown) → start (recreate) → stop → …
    @Volatile
    private var executorService: ExecutorService = Executors.newCachedThreadPool()
    @Volatile
    private var peerThreadPool: ExecutorService = Executors.newCachedThreadPool()

    private val acceptableBlockHeightDifference = 50_000
    private var peerCountToConnectMax = 100
    private var peerCountToConnect: Int? = null // number of peers to connect to
    private val peerCountToHold = peerSize      // number of peers held
    private var peerCountConnected = 0          // number of peers connected to

    private var lastLogTime = 0L
    private val logIntervalMs = 60_000L
    private var lastPeerAddressRefreshTime = 0L
    private val peerAddressRefreshIntervalMs = 60_000L

    @Synchronized
    open fun start() {
        if (running) {
            return
        }

        if (executorService.isShutdown) {
            executorService = Executors.newCachedThreadPool()
        }
        if (peerThreadPool.isShutdown) {
            peerThreadPool = Executors.newCachedThreadPool()
        }

        running = true
        peerCountConnected = 0
        peerGroupListeners.forEach { it.onStart() }
        connectPeersIfRequired()
    }

    @Synchronized
    open fun stop() {
        running = false
        peerManager.disconnectAll()
        getAddrRequestedHosts.clear()
        acceptedPeerHosts.clear()
        peerGroupListeners.forEach { it.onStop() }
        // Release the per-PeerGroup thread pools. Without this, a fresh PeerGroup
        // is created on every restart while the previous one keeps its peer-worker
        // threads alive (PeerConnection.run() busy-loops with Thread.sleep + the
        // pools use newCachedThreadPool without an upper bound), so the OS thread
        // count grows unboundedly across the session. On low-memory devices that
        // pressure can manifest as SIGABRT in dependent native libraries.
        // Soft shutdown is enough: disconnectAll() already signalled every
        // PeerConnection.run() to exit, and no new tasks will be accepted.
        peerThreadPool.shutdown()
        executorService.shutdown()
    }

    fun refresh() {
        if (running) {
            peerGroupListeners.forEach { it.onRefresh() }
        }
    }

    fun addPeerGroupListener(listener: Listener) {
        peerGroupListeners.add(listener)
    }

    fun removePeerGroupListener(listener: Listener) {
        peerGroupListeners.remove(listener)
    }

    fun addPeers(peers: List<String>) {
        hostManager.addIps(null, peers)
    }

    fun getPeerManager(): PeerManager {
        return peerManager
    }

    //
    // PeerListener implementations
    //
    override fun onConnect(peer: Peer) {
        acceptedPeerHosts.add(peer.host)
        hostManager.markConnected(peer)
        peerGroupListeners.forEach { it.onPeerConnect(peer) }

        peerCountToConnect?.let { disconnectSlowestPeer(it) } ?: setPeerCountToConnect(peer)
    }

    override fun onReady(peer: Peer) {
        peerGroupListeners.forEach { it.onPeerReady(peer) }
    }

    override fun onDisconnect(peer: Peer, e: Exception?) {
        peerManager.remove(peer)
        getAddrRequestedHosts.remove(peer.host)
        val acceptedPeer = acceptedPeerHosts.remove(peer.host)

        when (e) {
            null -> {
                Timber.tag(network.logTag).i("Peer ${peer.host} disconnected.")
                hostManager.markSuccess(peer.host)
            }

            is PeerTimer.Error.Timeout -> {
                Timber.tag(network.logTag).w("Peer ${peer.host} disconnected. Warning: ${e.javaClass.simpleName}, ${e.message}.")
                if (acceptedPeer) {
                    hostManager.markSuccess(peer.host)
                } else {
                    hostManager.markFailed(peer.host)
                }
            }

            else -> {
                Timber.tag(network.logTag).w("Peer ${peer.host} disconnected. Error: ${e.javaClass.simpleName}, ${e.message}.")
                hostManager.markFailed(peer.host)
            }
        }

        peerGroupListeners.forEach { it.onPeerDisconnect(peer, e) }
        connectPeersIfRequired()
    }

    override fun onReceiveMessage(peer: Peer, message: IMessage) {
        when {
            message is RejectMessage -> {
                peerGroupListeners.forEach { it.onPeerReject(peer, message) }
            }

            message is AddrMessage && handleAddrMessage -> {
                val peerIps = message.addresses
                    // exclude peers those don't support bloom filter
                    .filter {
                        it.services and network.serviceBloomFilter == network.serviceBloomFilter
                    }
                    .map {
                        InetAddress.getByAddress(it.address)
                    }
                    // exclude ipv6 addresses
                    .filter {
                        it !is Inet6Address
                    }
                    .mapNotNull {
                        it.hostAddress
                    }

                hostManager.addIps(null, peerIps)
            }

            message is InvMessage -> {
                inventoryItemsHandlers.forEach { it.handleInventoryItems(peer, message.inventory) }
            }
        }
        logPeersStatusThrottled()
    }

    override fun onPongMessage() {
        logPeersStatusThrottled()
    }

    private fun logPeersStatusThrottled() {
        if(Timber.treeCount == 0) {
            return
        }
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLogTime >= logIntervalMs) {
            Timber.tag(network.logTag).d("Peers status: ${peerManager.peersCount} connected, ${peerManager.readyPears().size} ready, isSynced: ${peerManager.hasSyncedPeer()}")
            lastLogTime = currentTime
        }
    }

    override fun onTaskComplete(peer: Peer, task: PeerTask) {
        peerTaskHandlers.any { it.handleCompletedTask(peer, task) }
    }

    //
    // PeerAddressManager Listener
    //
    override fun onAddAddress() {
        connectPeersIfRequired()
    }

    //
    // Private methods
    //

    private fun setPeerCountToConnect(peer: Peer) {
        peerCountToConnect = if (peer.announcedLastBlockHeight - localDownloadedBestBlockHeight > acceptableBlockHeightDifference) {
            peerCountToConnectMax
        } else {
            0
        }
    }

    private fun disconnectSlowestPeer(peerCountToConnect: Int) {
        if (peerCountToConnect > peerCountConnected && peerCountToHold > 1 && hostManager.hasFreshIps) {
            val sortedPeers = peerManager.sorted()
            if (sortedPeers.size >= peerCountToHold) {
                sortedPeers.lastOrNull()?.close(
                    Peer.Error("Slowest peer")
                )
            }
        }
    }

    @Synchronized
    private fun connectPeersIfRequired() {
        if (!running || !connectionManager.isConnected) {
            return
        }

        for (i in peerManager.peersCount until peerCountToHold) {
            val ip = hostManager.getIp() ?: break
            val peer = Peer(ip, network, this, networkMessageParser, networkMessageSerializer, executorService)
            peerCountConnected += 1
            peerGroupListeners.forEach { it.onPeerCreate(peer) }
            peerManager.add(peer)
            peer.start(peerThreadPool)
        }

        if (peerManager.peersCount > 0 && peerManager.peersCount < peerCountToHold) {
            requestMorePeerAddressesIfNeeded()
        }
        refreshPeerAddressesIfNeeded()
    }

    private fun requestMorePeerAddressesIfNeeded() {
        val connectedPeers = peerManager.connected()
        if (connectedPeers.isEmpty()) return

        connectedPeers
            .filter { getAddrRequestedHosts.add(it.host) }
            .forEach { it.sendGetAddrMessage() }
    }

    private fun refreshPeerAddressesIfNeeded() {
        if (peerManager.connected().size >= peerCountToHold) return

        val now = System.currentTimeMillis()
        if (now - lastPeerAddressRefreshTime < peerAddressRefreshIntervalMs) return

        lastPeerAddressRefreshTime = now
        hostManager.refreshPeerAddresses()
    }

    //
    // PeerGroup Exceptions
    //
    class Error(message: String) : Exception(message)
}
