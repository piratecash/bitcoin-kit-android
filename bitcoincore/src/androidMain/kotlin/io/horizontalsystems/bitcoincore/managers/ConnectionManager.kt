package io.horizontalsystems.bitcoincore.managers

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IConnectionManagerListener
import java.lang.ref.WeakReference

class ConnectionManager private constructor(context: Context) : IConnectionManager {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val listeners = mutableListOf<WeakReference<IConnectionManagerListener>>()
    private val listenersLock = Any()
    private val lock = Any()
    private val activeNetworks = mutableSetOf<Network>()
    private val validatedNetworks = mutableSetOf<Network>()
    private val callback = ConnectionStatusCallback()

    @Volatile
    override var isConnected: Boolean = false
        private set

    init {
        registerCallback()
        updateInitialState()
    }

    override fun addListener(listener: IConnectionManagerListener) {
        synchronized(listenersLock) {
            cleanupListenersLocked()
            val alreadyRegistered = listeners.any { it.get() === listener }
            if (!alreadyRegistered) {
                listeners.add(WeakReference(listener))
            }
        }
        listener.onConnectionChange(isConnected)
    }

    override fun removeListener(listener: IConnectionManagerListener) {
        synchronized(listenersLock) {
            cleanupListenersLocked()
            listeners.removeAll { it.get() === listener }
        }
    }

    override fun onEnterForeground() {
        registerCallback()
        updateInitialState()
    }

    override fun onEnterBackground() {
        // No-op: keep monitoring for the lifetime of the process.
    }

    private fun registerCallback() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) { }

        try {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                callback
            )
        } catch (_: Exception) {
        }
    }

    private fun updateInitialState() {
        val changed = synchronized(lock) {
            activeNetworks.clear()
            validatedNetworks.clear()

            connectivityManager.allNetworks.forEach { network ->
                activeNetworks.add(network)

                connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                    if (capabilities.isValidInternet()) {
                        validatedNetworks.add(network)
                    }
                }
            }

            updateConnectionStateLocked()
        }

        if (changed) {
            notifyListeners()
        }
    }

    private fun updateConnectionStateLocked(): Boolean {
        val previous = isConnected

        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let {
            connectivityManager.getNetworkCapabilities(it)
        }

        isConnected = capabilities?.isValidInternet() == true

        return previous != isConnected
    }

    private fun NetworkCapabilities.isValidInternet(): Boolean {
        return hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun notifyListeners() {
        val snapshot = synchronized(listenersLock) {
            cleanupListenersLocked()
            listeners.mapNotNull { it.get() }
        }

        snapshot.forEach { listener ->
            listener.onConnectionChange(isConnected)
        }
    }

    private fun cleanupListenersLocked() {
        listeners.removeAll { it.get() == null }
    }

    private inner class ConnectionStatusCallback : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            val changed = synchronized(lock) {
                activeNetworks.add(network)

                connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                    if (capabilities.isValidInternet()) {
                        validatedNetworks.add(network)
                    }
                }

                updateConnectionStateLocked()
            }

            if (changed) {
                notifyListeners()
            }
        }

        override fun onLost(network: Network) {
            val changed = synchronized(lock) {
                activeNetworks.remove(network)
                validatedNetworks.remove(network)
                updateConnectionStateLocked()
            }

            if (changed) {
                notifyListeners()
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val changed = synchronized(lock) {
                if (networkCapabilities.isValidInternet()) {
                    validatedNetworks.add(network)
                } else {
                    validatedNetworks.remove(network)
                }

                updateConnectionStateLocked()
            }

            if (changed) {
                notifyListeners()
            }
        }
    }

    companion object {
        @Volatile
        private var instance: ConnectionManager? = null

        fun getInstance(context: Context): ConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: ConnectionManager(context).also { instance = it }
            }
        }
    }
}
