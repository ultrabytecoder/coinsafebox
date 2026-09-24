package com.ultrabytecoder.coinsafebox.data

import com.ultrabytecoder.coinsafebox.db.CoinSafeBoxDatabase

/**
 * Lazy accessor for the opened database. Repositories depend on this instead of a
 * directly constructed [CoinSafeBoxDatabase], because the DB can only be opened after
 * the user unlocks the session with the PIN and the DEK is known.
 */
interface DatabaseProvider {
    /** @throws IllegalStateException when the session is locked. */
    fun database(): CoinSafeBoxDatabase
}