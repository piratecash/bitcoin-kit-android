package io.horizontalsystems.bitcoincore.managers

import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IConnectionManagerListener

/** Desktop has no connectivity service to subscribe to, so the kit always syncs. */
class ConnectionManager : IConnectionManager {

    override val isConnected = true

    override fun addListener(listener: IConnectionManagerListener) {
        listener.onConnectionChange(isConnected)
    }

    override fun removeListener(listener: IConnectionManagerListener) = Unit

    override fun onEnterForeground() = Unit

    override fun onEnterBackground() = Unit
}
