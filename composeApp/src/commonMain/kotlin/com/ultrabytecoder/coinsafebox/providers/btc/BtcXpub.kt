package com.ultrabytecoder.coinsafebox.providers

import fr.acinq.bitcoin.DeterministicWallet

object BtcXpub {
    /** Encode the account-level (m/84'/coin/index') public key as a BIP-84 zpub (mainnet) or vpub (testnet). */
    fun fromMasterKey(masterKey: DeterministicWallet.ExtendedPrivateKey, accountPath: String, testnet: Boolean): String =
        DeterministicWallet.encode(
            masterKey.derivePrivateKey(accountPath).extendedPublicKey,
            if (testnet) DeterministicWallet.vpub else DeterministicWallet.zpub
        )

    /** Decode a stored zpub/vpub into the account-level ExtendedPublicKey (public key material only). */
    fun decode(xpub: String): DeterministicWallet.ExtendedPublicKey =
        DeterministicWallet.ExtendedPublicKey.decode(xpub).second
}
