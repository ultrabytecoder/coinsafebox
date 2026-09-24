package com.ultrabytecoder.coinsafebox

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.fragment.app.FragmentActivity
import com.ultrabytecoder.coinsafebox.ui.keyboard.platform.SystemKeyboardBlockerContext
import com.ultrabytecoder.coinsafebox.ui.util.applySecureFlag

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        window.applySecureFlag()

        // Register the activity so secure screens can scope a one-shot system-IME
        // dismiss to it. blockSystemKeyboard() is invoked from AppKeyboard only
        // while the on-screen keyboard is showing — never app-wide — so non-secure
        // fields elsewhere keep normal system-IME behavior.
        SystemKeyboardBlockerContext.register(this)

        setContent {
            App()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Drop the process-wide Activity reference so blockSystemKeyboard() can't
        // act on a stale activity after process/activity recreation.
        SystemKeyboardBlockerContext.clear()
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}