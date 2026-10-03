package com.ultrabytecoder.coinsafebox.data.walletconnect

import java.util.concurrent.locks.ReentrantLock

internal actual fun newWcLockHandle(): Any = ReentrantLock()
internal actual fun wcLockAcquire(handle: Any) = (handle as ReentrantLock).lock()
internal actual fun wcLockRelease(handle: Any) = (handle as ReentrantLock).unlock()
