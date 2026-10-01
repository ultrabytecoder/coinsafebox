package com.ultrabytecoder.coinsafebox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.ComposeUIViewController
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.di.appModule
import com.ultrabytecoder.coinsafebox.di.platformModule
import com.ultrabytecoder.coinsafebox.platform.IosScreenshotProtector
import com.ultrabytecoder.coinsafebox.security.SessionLockNotifier
import com.ultrabytecoder.coinsafebox.security.SessionManager
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSObjectProtocol
import platform.UIKit.UIApplicationWillResignActiveNotification

fun MainViewController() = ComposeUIViewController {
    KoinApplication(
        application = {
            val apiKey = NSBundle.mainBundle.objectForInfoDictionaryKey("EtherscanApiKey") as? String ?: ""
            val wcProjectId = NSBundle.mainBundle.objectForInfoDictionaryKey("WcProjectId") as? String ?: ""
            // Network + Etherscan key are injected by the host app's Info.plist
            // (per Testnet/Mainnet Xcode build configuration). Defaults to testnet.
            val network = NSBundle.mainBundle.objectForInfoDictionaryKey("Network") as? String ?: "testnet"
            val networkConfig = if (network == "mainnet") {
                NetworkConfig.mainnet(apiKey, wcProjectId)
            } else {
                NetworkConfig.testnet(apiKey, wcProjectId)
            }
            modules(appModule(networkConfig), platformModule)
        }
    ) {
        // The app is resigning active (app switcher, background): lock the session
        // (close the DB, zero the DEK) and tell the UI to return to the PIN screen.
        // The IosScreenshotProtector shows a synchronous black overlay window on
        // resign-active — the system captures the app-switcher snapshot BEFORE the
        // async SwiftUI scenePhase overlay can render, so only the UIKit overlay
        // guarantees the snapshot is obscured (NEW-10).
        val sessionManager: SessionManager = koinInject()
        DisposableEffect(Unit) {
            val protector = IosScreenshotProtector().also { it.enable() }
            val observer: NSObjectProtocol? = NSNotificationCenter.defaultCenter.addObserverForName(
                name = UIApplicationWillResignActiveNotification,
                `object` = null,
                queue = null
            ) { _ ->
                sessionManager.lock()
                SessionLockNotifier.notifyLocked()
            }
            onDispose {
                observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
                protector.disable()
            }
        }
        App()
    }
}