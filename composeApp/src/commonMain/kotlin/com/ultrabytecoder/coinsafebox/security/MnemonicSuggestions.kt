package com.ultrabytecoder.coinsafebox.security

import fr.acinq.bitcoin.MnemonicCode

object MnemonicSuggestions {

    private const val MAX_RESULTS = 8
    private const val MIN_PREFIX_LENGTH = 1

    private val WORDLIST: List<String> = MnemonicCode.englishWordlist

    fun forPrefix(prefix: String, limit: Int = MAX_RESULTS): List<String> {
        if (prefix.length < MIN_PREFIX_LENGTH) return emptyList()
        if (WORDLIST.binarySearch(prefix) >= 0) return emptyList()
        return WORDLIST
            .asSequence()
            .filter { it.startsWith(prefix) }
            .take(limit)
            .toList()
    }
}
