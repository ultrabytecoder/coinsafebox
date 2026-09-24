package com.ultrabytecoder.coinsafebox.data

/**
 * Key/value settings contract. [SettingsStorage] is the platform-backed
 * implementation; tests use an in-memory fake of this interface so they never
 * touch the real on-disk store (see KeyManagerTest).
 */
interface SettingsStore {
    fun putString(key: String, value: String)
    fun getString(key: String): String?
    fun remove(key: String)
}

expect class SettingsStorage(context: Any? = null) : SettingsStore {
    override fun putString(key: String, value: String)
    override fun getString(key: String): String?
    override fun remove(key: String)
}

/**
 * Storage keys for per-chain custom RPC/API node overrides.
 * A missing value means "use the NetworkConfig default".
 */
object CustomNodeKeys {
    const val BTC = "custom_node_btc"
    const val ETH = "custom_node_eth"
    const val TRX = "custom_node_trx"
    const val TON = "custom_node_ton"
}

object SettingsKeys {
    const val FIAT_CURRENCY = "fiat_currency"
    const val SECURITY_METHOD = "security_method"
    const val PIN_LENGTH = "pin_length"
}