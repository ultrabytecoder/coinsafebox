package com.ultrabytecoder.coinsafebox.security

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

/**
 * AWT global event listener: any mouse/keyboard event counts as user activity
 * and resets the session idle timeout (F-5).
 */
actual fun installIdleHook(callback: () -> Unit) {
    Toolkit.getDefaultToolkit().addAWTEventListener({ event: AWTEvent ->
        if (event is MouseEvent || event is KeyEvent) {
            callback()
        }
    }, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.KEY_EVENT_MASK)
}
