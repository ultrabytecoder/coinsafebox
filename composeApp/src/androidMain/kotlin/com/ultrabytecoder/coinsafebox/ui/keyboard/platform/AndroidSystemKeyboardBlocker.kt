package com.ultrabytecoder.coinsafebox.ui.keyboard.platform

import android.app.Activity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.lang.ref.WeakReference

object SystemKeyboardBlockerContext {
    private var activityRef: WeakReference<Activity>? = null

    fun register(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun getActivity(): Activity? = activityRef?.get()

    fun clear() {
        activityRef = null
    }
}

/**
 * Dismisses any currently-showing system soft keyboard for the current activity.
 *
 * This is a one-shot, non-persistent dismiss: it does NOT set a window soft-input
 * mode flag, so it never blocks the system IME on non-secure fields elsewhere in
 * the app (search, send address, node URL, …). It is scoped to secure screens —
 * invoked from [AppKeyboard] only while the on-screen keyboard is showing. The
 * secure fields themselves are focusable(false) and the custom keyboard is
 * touch-driven, so the system IME cannot observe secure input regardless; this is
 * defense-in-depth against a lingering keyboard from a previous screen.
 */
actual fun blockSystemKeyboard() {
    SystemKeyboardBlockerContext.getActivity()?.let { activity ->
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            hide(WindowInsetsCompat.Type.ime())
        }
    }
}
