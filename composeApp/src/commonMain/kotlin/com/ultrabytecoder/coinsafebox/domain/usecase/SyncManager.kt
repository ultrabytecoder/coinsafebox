package com.ultrabytecoder.coinsafebox.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Per-account dedupe for the two independent syncs. Balance and transactions sync
 * are tracked separately so they can run concurrently for the same account (they
 * touch different tables) and each screen can bind to its relevant spinner signal.
 */
class SyncManager {
    private val balanceLocks = mutableMapOf<String, Mutex>()
    private val transactionsLocks = mutableMapOf<String, Mutex>()

    private val _syncingBalanceAccounts = MutableStateFlow(emptySet<String>())
    val syncingBalanceAccounts: StateFlow<Set<String>> = _syncingBalanceAccounts.asStateFlow()

    private val _syncingTransactionsAccounts = MutableStateFlow(emptySet<String>())
    val syncingTransactionsAccounts: StateFlow<Set<String>> = _syncingTransactionsAccounts.asStateFlow()

    fun tryAcquireBalance(accountId: String): Boolean {
        val lock = balanceLocks.getOrPut(accountId) { Mutex() }
        val acquired = lock.tryLock()
        if (acquired) {
            _syncingBalanceAccounts.value = _syncingBalanceAccounts.value + accountId
        }
        return acquired
    }

    fun releaseBalance(accountId: String) {
        balanceLocks[accountId]?.unlock()
        _syncingBalanceAccounts.value = _syncingBalanceAccounts.value - accountId
    }

    fun tryAcquireTransactions(accountId: String): Boolean {
        val lock = transactionsLocks.getOrPut(accountId) { Mutex() }
        val acquired = lock.tryLock()
        if (acquired) {
            _syncingTransactionsAccounts.value = _syncingTransactionsAccounts.value + accountId
        }
        return acquired
    }

    fun releaseTransactions(accountId: String) {
        transactionsLocks[accountId]?.unlock()
        _syncingTransactionsAccounts.value = _syncingTransactionsAccounts.value - accountId
    }
}
