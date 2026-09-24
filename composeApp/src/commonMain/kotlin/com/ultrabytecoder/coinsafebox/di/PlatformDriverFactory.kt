package com.ultrabytecoder.coinsafebox.di

import com.ultrabytecoder.coinsafebox.security.DbSessionFactory

/**
 * The platform's encrypted-database driver factory, wired into SessionManager.
 * Constructed directly per platform (it holds only the DB path) instead of being
 * resolved through Koin, which keeps test overrides trivial.
 */
internal expect fun platformDriverFactory(): DbSessionFactory
