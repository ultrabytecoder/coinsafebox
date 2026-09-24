package com.ultrabytecoder.coinsafebox.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSObjectProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIColor
import platform.UIKit.UIScreen
import platform.UIKit.UIScreenCapturedDidChangeNotification
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowLevelAlert
import platform.UIKit.UIWindowScene

class IosScreenshotProtector : ScreenshotProtector {

    private var overlayWindow: UIWindow? = null
    private var capturedObserver: NSObjectProtocol? = null
    private var willResignObserver: NSObjectProtocol? = null
    private var didBecomeActiveObserver: NSObjectProtocol? = null

    override fun enable() {
        require(overlayWindow == null) { "ScreenshotProtector.enable() called twice without disable()" }

        val scene = foregroundWindowScene()
        val bounds = scene?.coordinateSpace?.bounds ?: UIScreen.mainScreen.bounds

        // Build a black overlay window. UIWindow's root layer + backgroundColor
        // is what gets captured by the system snapshot pipeline.
        overlayWindow = UIWindow(bounds).apply {
            windowScene = scene
            windowLevel = UIWindowLevelAlert + 1.0
            backgroundColor = UIColor.blackColor()
            hidden = true
            rootViewController = UIViewController().apply {
                view?.backgroundColor = UIColor.blackColor()
            }
        }

        val center = NSNotificationCenter.defaultCenter

        capturedObserver = center.addObserverForName(
            name = UIScreenCapturedDidChangeNotification,
            `object` = null,
            queue = null
        ) { _ -> syncOverlay() }

        willResignObserver = center.addObserverForName(
            name = UIApplicationWillResignActiveNotification,
            `object` = null,
            queue = null
        ) { _ -> showOverlay() }

        didBecomeActiveObserver = center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null
        ) { _ -> syncOverlay() }

        syncOverlay()
    }

    override fun disable() {
        capturedObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        willResignObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        didBecomeActiveObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        capturedObserver = null
        willResignObserver = null
        didBecomeActiveObserver = null

        overlayWindow?.let { win ->
            win.hidden = true
            win.rootViewController = null
        }
        overlayWindow = null
    }

    private fun syncOverlay() {
        val captured = UIScreen.mainScreen.isCaptured
        overlayWindow?.hidden = !captured
    }

    private fun showOverlay() {
        overlayWindow?.hidden = false
    }

    private fun foregroundWindowScene(): UIWindowScene? {
        return UIApplication.sharedApplication
            .connectedScenes
            .filterIsInstance<UIWindowScene>()
            .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
            ?: UIApplication.sharedApplication
                .connectedScenes
                .filterIsInstance<UIWindowScene>()
                .firstOrNull()
    }
}

@Composable
actual fun rememberScreenshotProtector(): ScreenshotProtector {
    return remember { IosScreenshotProtector() }
}