package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Design A (xpub) correctness: a stored account-level xpub (m/84'/coin/index')
 * must let the read-only provider derive every receive (`0/i`) and change
 * (`1/i`) address by PUBLIC derivation, byte-identical to the addresses the
 * full (private) key would derive. A mismatch here would silently break
 * read-only sync (missing change UTXOs / receive addresses) or, worse, make
 * the wrong-mnemonic guard compare against a wrong address.
 */
class BtcXpubDerivationTest {

    companion object {
        // Seed for "abandon ... about" (empty passphrase) — the canonical BIP-39 vector.
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val ACCOUNT_PATH = "m/84'/1'/0'" // testnet (coin type 1), account 0
    }

    private val networkConfig = NetworkConfig.testnet("test-api-key")

    private val masterKey: DeterministicWallet.ExtendedPrivateKey =
        DeterministicWallet.generate(Hex.decode(SEED_HEX))

    private val accountKey: DeterministicWallet.ExtendedPrivateKey =
        masterKey.derivePrivateKey(ACCOUNT_PATH)

    private val xpub: DeterministicWallet.ExtendedPublicKey =
        BtcXpub.decode(BtcXpub.fromMasterKey(masterKey, ACCOUNT_PATH, testnet = true))

    private fun privateAddress(chain: Int, index: Long): String =
        Bitcoin.computeBIP84Address(
            accountKey.derivePrivateKey(listOf(chain.toLong(), index)).publicKey,
            networkConfig.btcGenesisBlockHash
        )

    private fun xpubAddress(chain: Int, index: Long): String =
        Bitcoin.computeBIP84Address(
            xpub.derivePublicKey(listOf(chain.toLong(), index)).publicKey,
            networkConfig.btcGenesisBlockHash
        )

    @Test
    fun xpubDerivedReceiveAddressesMatchPrivateDerivation() {
        for (i in 0L..4L) {
            assertEquals(privateAddress(0, i), xpubAddress(0, i), "receive 0/$i must match")
        }
    }

    @Test
    fun xpubDerivedChangeAddressesMatchPrivateDerivation() {
        for (i in 0L..4L) {
            assertEquals(privateAddress(1, i), xpubAddress(1, i), "change 1/$i must match")
        }
    }

    @Test
    fun receiveAndChangeChainsProduceDistinctAddresses() {
        assertNotEquals(xpubAddress(0, 0), xpubAddress(1, 0), "0/0 and 1/0 must differ")
    }

    @Test
    fun xpubDecodeRoundTripsToSameKeyMaterial() {
        val encoded = BtcXpub.fromMasterKey(masterKey, ACCOUNT_PATH, testnet = true)
        val decoded = BtcXpub.decode(encoded)
        // decode() rebuilds the path from an empty parent, so compare the
        // cryptographic key material (not the path metadata).
        assertEquals(accountKey.extendedPublicKey.publicKey, decoded.publicKey, "decoded public key must match")
        assertEquals(accountKey.extendedPublicKey.chaincode, decoded.chaincode, "decoded chain code must match")
    }

    @Test
    fun wrongAccountIndexDoesNotMatch() {
        // A different account index must derive a DIFFERENT key — guards against
        // storing an xpub for the wrong account path.
        val otherXpub = BtcXpub.decode(
            BtcXpub.fromMasterKey(masterKey, "m/84'/1'/1'", testnet = true)
        )
        val otherAddr = Bitcoin.computeBIP84Address(
            otherXpub.derivePublicKey(listOf(0L, 0L)).publicKey,
            networkConfig.btcGenesisBlockHash
        )
        assertNotEquals(privateAddress(0, 0), otherAddr, "account 1 xpub must not match account 0")
    }

    @Test
    fun storedXpubIsPublicMaterial() {
        val xpubString = BtcXpub.fromMasterKey(masterKey, ACCOUNT_PATH, testnet = true)
        // vpub = testnet BIP-84 (zpub on mainnet). Must not carry the private prefix.
        assertTrue(
            xpubString.startsWith("vpub") || xpubString.startsWith("zpub"),
            "expected a BIP-84 public prefix, got ${xpubString.take(5)}"
        )
        // Decoding must not require any secret, and must recover the account's public key.
        assertEquals(accountKey.extendedPublicKey.publicKey, BtcXpub.decode(xpubString).publicKey)
    }
}
