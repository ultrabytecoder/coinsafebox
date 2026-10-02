package com.ultrabytecoder.coinsafebox

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.di.appModule
import com.ultrabytecoder.coinsafebox.di.platformModule
import com.ultrabytecoder.coinsafebox.security.SessionManager
import com.ultrabytecoder.coinsafebox.security.installIdleHook
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level

fun main() = application {
    // Network is baked in at build time (DesktopBuildConfig, via -PkkNetwork).
    // A -Dcoinsafebox.network=mainnet|testnet JVM property still overrides for dev.
    val useMainnet = when (System.getProperty("coinsafebox.network")) {
        "mainnet" -> true
        "testnet" -> false
        else -> !DesktopBuildConfig.IS_TESTNET
    }
    val etherscanKey = System.getProperty("coinsafebox.etherscan.key", DesktopBuildConfig.ETHERSCAN_API_KEY)
    val wcProjectId = System.getProperty("coinsafebox.wc.project.id", DesktopBuildConfig.WC_PROJECT_ID)
    val networkConfig = if (useMainnet) {
        NetworkConfig.mainnet(etherscanApiKey = etherscanKey, wcProjectId = wcProjectId)
    } else {
        NetworkConfig.testnet(etherscanApiKey = etherscanKey, wcProjectId = wcProjectId)
    }

    startKoin {
        logger(org.koin.core.logger.PrintLogger(Level.ERROR))
        modules(appModule(networkConfig), platformModule)
    }

    // User activity (mouse/keyboard) resets the session idle timeout (F-5).
    installIdleHook {
        runCatching {
            org.koin.core.context.GlobalContext.get().get<SessionManager>().registerActivity()
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "CoinSafeBox",
        state = androidx.compose.ui.window.rememberWindowState(
            width = 420.dp,
            height = 780.dp
        )
    ) {
        App()
    }
}
