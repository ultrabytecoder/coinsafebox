package com.ultrabytecoder.coinsafebox.security

/**
 * JVM/Android zeroization. Writing the array reference through a @Volatile sink
 * forces the zeroed contents to be observable by other threads, so the JIT cannot
 * prove the fill is dead and eliminate it (dead-store elimination).
 * Best-effort by JMM definition, but the accepted practice on ART/HotSpot.
 */
private @Volatile var byteArraySink: ByteArray? = null

private @Volatile var charArraySink: CharArray? = null

actual fun ByteArray.wipe() {
    if (isEmpty()) return
    fill(0)
    byteArraySink = this
    byteArraySink = null
}

actual fun CharArray.wipe() {
    if (isEmpty()) return
    fill('\u0000')
    charArraySink = this
    charArraySink = null
}

actual fun gcHint() {
    // Best-effort hint on ART/HotSpot; may be ignored.
    System.gc()
}
