package com.ultrabytecoder.coinsafebox.domain.exception

class ReadOnlyWalletException(message: String = "This wallet is read-only") : Exception(message)

class WrongMnemonicException(message: String = "The recovery phrase does not match this wallet") : Exception(message)
