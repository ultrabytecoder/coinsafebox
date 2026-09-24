package com.ultrabytecoder.coinsafebox.security

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Cross-platform notification that the session was locked because the app went to
 * the background (see SessionManager.lock). The UI observes it to:
 *  - navigate back to the PIN unlock screen, and
 *  - wipe any sensitive state still held in ViewModels (e.g. a displayed mnemonic).
 */
object SessionLockNotifier {

    private val _locked = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val locked: Flow<Unit> = _locked

    fun notifyLocked() {
        _locked.tryEmit(Unit)
    }
}