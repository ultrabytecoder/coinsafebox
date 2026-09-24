package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.providers.ton.TonBase
import com.ultrabytecoder.coinsafebox.providers.ton.TonWalletVersion
import com.ultrabytecoder.coinsafebox.providers.ton.boc.*
import com.ultrabytecoder.coinsafebox.providers.ton.wallet.WalletContractV3R2
import com.ultrabytecoder.coinsafebox.providers.ton.wallet.WalletContractV4
import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TonAddressDerivationTest {

    companion object {
        private const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        private const val USER_EXPECTED_PUB_KEY_HEX =
            "7952e94118f34607c75e23258dd9220d66ccac5a3ee074125c25068e8107bfbf"

        private const val ADDRESS = "EQC9LJL69GjPHMTyZ_9_P2QKTGnQhMWs2eRk6MVDiDina5Lf"
    }

    private class TestTonBase(masterSeed: ByteArray) : TonBase(
        masterSeed,
        NetworkConfig.testnet("test-key")
    ) {
        fun deriveKey(index: Long) = deriveTonKey(index)
        fun addressFromPublicKey(
            publicKey: ByteArray,
            version: TonWalletVersion = TonWalletVersion.V3R2,
            subwalletId: Int = DEFAULT_WALLET_ID
        ) = tonAddressFromPublicKey(publicKey, version, subwalletId)
    }

    private fun createTestBase(): TestTonBase = TestTonBase(MnemonicCode.toSeed(MNEMONIC, ""))

    @Test
    fun testKeyDerivation() {
        val base = createTestBase()
        val keyPair = base.deriveKey(0)
        val derivedPub = hexEncode(keyPair.publicKey)
        assertEquals(USER_EXPECTED_PUB_KEY_HEX, derivedPub)
    }

    @Test
    fun address_isValidTonUserFriendlyFormat() {
        val base = createTestBase()
        val keyPair = base.deriveKey(0)
        val address = base.addressFromPublicKey(keyPair.publicKey)
        assertEquals(48, address.length)
        assertTrue(address.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })
    }

    @Test
    fun address_decodesToCorrectWorkchain() {
        val base = createTestBase()
        val keyPair = base.deriveKey(0)
        val wallet = WalletContractV3R2.create(0, keyPair.publicKey)
        assertEquals(0, wallet.address.workChain)
    }

    @Test
    fun v3r2Address_matchesManualConstruction() {
        val base = createTestBase()
        val keyPair = base.deriveKey(0)
        val kotlinAddress = base.addressFromPublicKey(keyPair.publicKey, TonWalletVersion.V3R2)
        val walletAddress = WalletContractV3R2.create(0, keyPair.publicKey).address.toString()
        assertEquals(walletAddress, kotlinAddress)
        assertEquals(ADDRESS, walletAddress)
    }

    @Test
    fun v3r2AndV4r2_produceDifferentAddresses() {
        val base = createTestBase()
        val keyPair = base.deriveKey(0)
        val v3r2Address = base.addressFromPublicKey(keyPair.publicKey, TonWalletVersion.V3R2)
        val v4r2Address = base.addressFromPublicKey(keyPair.publicKey, TonWalletVersion.V4R2)
        assertTrue(v3r2Address != v4r2Address)
    }

    @Test
    fun allVersions_produceValidAddresses() {
        val base = createTestBase()
        val keyPair = base.deriveKey(0)
        for (version in TonWalletVersion.entries) {
            val address = base.addressFromPublicKey(keyPair.publicKey, version)
            assertEquals(48, address.length, "$version address should be 48 chars")
            assertTrue(
                address.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' },
                "$version address should only contain base64url chars"
            )
        }
    }

    private fun hexEncode(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }
}
