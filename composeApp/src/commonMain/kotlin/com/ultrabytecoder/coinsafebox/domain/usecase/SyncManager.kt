package com.ultrabytecoder.coinsafebox.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

class SyncManager {
    private val syncLocks = mutableMapOf<String, Mutex>()

    private val _syncingAccounts = MutableStateFlow(emptySet<String>())
    val syncingAccounts: StateFlow<Set<String>> = _syncingAccounts.asStateFlow()

    fun tryAcquire(accountId: String): Boolean {
        val lock = syncLocks.getOrPut(accountId) { Mutex() }
        val acquired = lock.tryLock()
        if (acquired) {
            _syncingAccounts.value = _syncingAccounts.value + accountId
        }
        return acquired
    }

    fun release(accountId: String) {
        syncLocks[accountId]?.unlock()
        _syncingAccounts.value = _syncingAccounts.value - accountId
    }
}
