package com.ultrabytecoder.coinsafebox.data.walletconnect

import com.ultrabytecoder.coinsafebox.data.SettingsStorage

interface WcKeyChain {
    fun has(tag: String): Boolean
    fun get(tag: String): String?
    fun set(tag: String, value: String)
    fun delete(tag: String)
    fun loadAll(): Map<String, String>
}

class SettingsWcKeyChain(
    private val storage: SettingsStorage,
    private val keyPrefix: String = "wc."
) : WcKeyChain {

    private val cache = mutableMapOf<String, String>()
    private val lock = WcLock()

    private fun storageKey(tag: String): String = "$keyPrefix$tag"

    override fun has(tag: String): Boolean = synchronized(lock) {
        if (cache.containsKey(tag)) return true
        val stored = storage.getString(storageKey(tag))
        if (stored != null) {
            cache[tag] = stored
            return true
        }
        false
    }

    override fun get(tag: String): String? = synchronized(lock) {
        cache[tag] ?: storage.getString(storageKey(tag))?.also { cache[tag] = it }
    }

    override fun set(tag: String, value: String) = synchronized(lock) {
        cache[tag] = value
        storage.putString(storageKey(tag), value)
    }

    override fun delete(tag: String) = synchronized(lock) {
        cache.remove(tag)
        storage.remove(storageKey(tag))
    }

    override fun loadAll(): Map<String, String> = synchronized(lock) {
        cache.toMap()
    }
}
