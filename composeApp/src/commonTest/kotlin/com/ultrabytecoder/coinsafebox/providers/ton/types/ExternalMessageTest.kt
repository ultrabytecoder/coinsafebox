package com.ultrabytecoder.coinsafebox.providers.ton.types

import com.ultrabytecoder.coinsafebox.providers.ton.address.TonAddress
import com.ultrabytecoder.coinsafebox.providers.ton.boc.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalMessageTest {

    @Test
    fun shouldBuildExtInMessageWithCorrectTag() {
        val walletAddress = TonAddress(0, ByteArray(32) { it.toByte() })
        val transferBody = beginCell().storeUint(42, 32).endCell()

        val extMsg = beginCell()
            .storeUint(0b10, 2)         // ext_in_msg_info$10
            .storeAddress(null)          // src: addr_none
            .storeAddress(walletAddress) // dest
            .storeCoins(0)               // import_fee
            .storeBit(false)             // no state init
            .storeBit(true)              // body as ref
            .storeRef(transferBody)
            .endCell()

        val reader = BitReader(extMsg.bits)
        assertEquals(0b10L, reader.loadUint(2), "Should be ext_in_msg_info tag (10 binary)")

        // src: addr_none
        assertEquals(0L, reader.loadUint(2), "src should be addr_none (00)")
    }

    @Test
    fun shouldBuildExtInMessageWithCorrectDest() {
        val walletAddress = TonAddress(0, ByteArray(32) { (it + 1).toByte() })
        val transferBody = beginCell().storeUint(0, 32).endCell()

        val extMsg = beginCell()
            .storeUint(0b10, 2)
            .storeAddress(null)
            .storeAddress(walletAddress)
            .storeCoins(0)
            .storeBit(false)
            .storeBit(true)
            .storeRef(transferBody)
            .endCell()

        val reader = BitReader(extMsg.bits)
        reader.loadUint(2) // tag
        reader.loadUint(2) // src (addr_none)

        // dest: addr_std$10
        assertEquals(2L, reader.loadUint(2), "dest tag should be addr_std (10)")
        assertEquals(0L, reader.loadUint(1), "no anycast")
        assertEquals(0L, reader.loadInt(8), "workchain should be 0")

        val destHash = reader.loadBuffer(32)
        assertTrue(
            destHash.contentEquals(walletAddress.hash),
            "dest hash should match wallet address hash"
        )
    }

    @Test
    fun shouldBuildExtInMessageWithImportFeeZero() {
        val walletAddress = TonAddress(0, ByteArray(32))
        val transferBody = beginCell().endCell()

        val extMsg = beginCell()
            .storeUint(0b10, 2)
            .storeAddress(null)
            .storeAddress(walletAddress)
            .storeCoins(0)
            .storeBit(false)
            .storeBit(true)
            .storeRef(transferBody)
            .endCell()

        val reader = BitReader(extMsg.bits)
        reader.loadUint(2) // tag
        reader.loadUint(2) // src
        reader.loadUint(2) // dest tag
        reader.loadUint(1) // anycast
        reader.loadInt(8)  // workchain
        reader.loadBuffer(32) // hash

        val importFee = reader.loadCoins()
        assertEquals(0L, importFee, "import_fee should be 0")
    }

    @Test
    fun shouldBuildExtInMessageWithBodyAsRef() {
        val walletAddress = TonAddress(0, ByteArray(32))
        val transferBody = beginCell().storeUint(0xABCDL, 16).endCell()

        val extMsg = beginCell()
            .storeUint(0b10, 2)
            .storeAddress(null)
            .storeAddress(walletAddress)
            .storeCoins(0)
            .storeBit(false)
            .storeBit(true)
            .storeRef(transferBody)
            .endCell()

        assertEquals(1, extMsg.refs.size, "Should have exactly 1 reference (the body)")
        assertTrue(
            extMsg.refs[0].hash().contentEquals(transferBody.hash()),
            "Reference should be the transfer body"
        )
    }

    @Test
    fun extInMessageRoundtripThroughBoC() {
        val walletAddress = TonAddress(0, ByteArray(32) { it.toByte() })
        val transferBody = beginCell()
            .storeUint(0x12345678L, 32)
            .storeUint(0xABCDEF00L, 32)
            .endCell()

        val extMsg = beginCell()
            .storeUint(0b10, 2)
            .storeAddress(null)
            .storeAddress(walletAddress)
            .storeCoins(0)
            .storeBit(false)
            .storeBit(true)
            .storeRef(transferBody)
            .endCell()

        val bocBytes = extMsg.toBoc()
        val restored = Cell.fromBoc(bocBytes)[0]

        assertTrue(
            extMsg.hash().contentEquals(restored.hash()),
            "External message should survive BoC roundtrip"
        )
    }

    @Test
    fun extInMessageWithStateInit() {
        val walletAddress = TonAddress(0, ByteArray(32))
        val codeCell = beginCell().storeUint(1, 32).endCell()
        val dataCell = beginCell().storeUint(2, 32).endCell()
        val transferBody = beginCell().storeUint(0, 32).endCell()

        val stateInitCell = beginCell()
            .storeBit(false) // no split_depth
            .storeBit(false) // no special
            .storeMaybeRef(codeCell)
            .storeMaybeRef(dataCell)
            .storeBit(false) // empty library
            .endCell()

        val extMsg = beginCell()
            .storeUint(0b10, 2)
            .storeAddress(null)
            .storeAddress(walletAddress)
            .storeCoins(0)
            .storeBit(true)  // has state init
            .storeBit(true)  // state init as ref
            .storeRef(stateInitCell)
            .storeBit(true)  // body as ref
            .storeRef(transferBody)
            .endCell()

        assertEquals(2, extMsg.refs.size, "Should have 2 refs (state init + body)")
    }
}
