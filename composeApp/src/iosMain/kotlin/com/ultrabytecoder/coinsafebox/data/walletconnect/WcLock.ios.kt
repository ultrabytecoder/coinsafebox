package com.ultrabytecoder.coinsafebox.data.walletconnect

import platform.Foundation.NSRecursiveLock

// NSRecursiveLock (not NSLock) to match the JVM ReentrantLock semantics: if a code path
// ever re-acquires the same lock on the same thread, it recurses instead of deadlocking.
internal actual fun newWcLockHandle(): Any = NSRecursiveLock()
internal actual fun wcLockAcquire(handle: Any) = (handle as NSRecursiveLock).lock()
internal actual fun wcLockRelease(handle: Any) = (handle as NSRecursiveLock).unlock()
