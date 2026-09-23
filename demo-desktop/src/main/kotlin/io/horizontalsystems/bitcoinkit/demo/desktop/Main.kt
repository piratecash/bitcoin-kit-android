package io.horizontalsystems.bitcoinkit.demo.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.horizontalsystems.bitcoincore.managers.ConnectionManager
import io.horizontalsystems.bitcoinkit.demo.DemoApp
import io.horizontalsystems.bitcoinkit.demo.DemoController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

fun main() {
    val root = File(System.getProperty("user.home"), ".bitcoin-kit-demo")
    val controller = DemoController(
        dataDir = root.subDirectory("data"),
        mwebDataDir = root.subDirectory("mweb"),
        connectionManager = ConnectionManager(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    )
    controller.init()

    application {
        Window(onCloseRequest = ::exitApplication, title = "Bitcoin Kit demo") {
            DemoApp(controller)
        }
    }
}

private fun File.subDirectory(name: String): String =
    File(this, name).apply { mkdirs() }.absolutePath
