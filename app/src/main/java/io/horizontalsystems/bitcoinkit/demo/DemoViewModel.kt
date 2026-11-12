package io.horizontalsystems.bitcoinkit.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bitcoincore.managers.ConnectionManager

class DemoViewModel : ViewModel() {

    val controller = DemoController(
        dataDir = requireNotNull(App.instance.getDatabasePath(KitFactory.WALLET_ID).parent),
        mwebDataDir = App.instance.noBackupFilesDir.absolutePath,
        connectionManager = ConnectionManager.getInstance(App.instance),
        scope = viewModelScope,
    )

    init {
        controller.init()
    }

    /** Cancelling [viewModelScope] stops the controller's own jobs, never the kits' networking. */
    override fun onCleared() {
        controller.dispose()
        super.onCleared()
    }
}
