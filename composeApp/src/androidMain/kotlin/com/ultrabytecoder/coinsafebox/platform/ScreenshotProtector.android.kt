package com.ultrabytecoder.coinsafebox.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ultrabytecoder.coinsafebox.ui.util.applySecureFlag
import com.ultrabytecoder.coinsafebox.ui.util.clearSecureFlag

class AndroidScreenshotProtector(
    private val activity: Activity
) : ScreenshotProtector {

    override fun enable() {
        activity.window.applySecureFlag()
    }

    override fun disable() {
        val window = activity.window ?: return
        window.clearSecureFlag()
    }
}

@Composable
actual fun rememberScreenshotProtector(): ScreenshotProtector {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    return remember(activity) { AndroidScreenshotProtector(activity) }
}

private fun Context.findActivity(): Activity {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    error("No Activity found in context chain — ScreenshotProtector must be used inside an Activity-hosted Compose hierarchy.")
}