package com.ultrabytecoder.coinsafebox.providers.ton.types

import com.ultrabytecoder.coinsafebox.providers.ton.address.TonAddress
import com.ultrabytecoder.coinsafebox.providers.ton.boc.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class MessageRelaxedTest {

    // Ported from ton-core MessageRelaxed.spec.ts
    @Test
    fun shouldSerializeAndDeserializeMessageRelaxedRoundtrip() {
        val stateBoc = "te6ccsEBAgEAkQA3kQFoYgBgSQkXjXbkhpC1sju4zUJsLIAoavunKbfNsPFbk9jXL6BfXhAAAAAAAAAAAAAAAAAAAQEAsA+KfqUAAAAAAAAAAEO5rKAIAboVCXedy2J0RCseg4yfdNFtU8/BfiaHVEPkH/ze1W+fABicYUqh1j9Lnqv9ZhECm0XNPaB7/HcwoBb3AJnYYfqByAvrwgCqR2XE"
        val cell = Cell.fromBoc(Base64.decode(stateBoc))[0]

        val parsed = parseMessageRelaxed(cell.beginParse())
        val stored = beginCell().storeWritable(storeMessageRelaxed(parsed)).endCell()

        assertTrue(
            cell.hash().contentEquals(stored.hash()),
            "Roundtrip should produce identical cell"
        )
    }

    @Test
    fun shouldStoreInternalMessageFieldsCorrectly() {
        val destAddress = TonAddress.parse("EQDtFpEwcFAEcRe5mLVh2N6C0xZFh1KQRq5QZMYt5-Jpbo8-")
        val value = 100_000_000L // 0.1 TON in nanotons

        val msg = internalMessage(to = destAddress, value = value, bounce = true)
        val cell = beginCell().storeWritable(storeMessageRelaxed(msg)).endCell()

        val reader = BitReader(cell.bits)

        // int_msg_info$0
        assertEquals(0L, reader.loadUint(1), "Should be int_msg_info$0")

        // ihr_disabled
        assertEquals(true, reader.loadBit(), "ihr_disabled should be true")

        // bounce
        assertEquals(true, reader.loadBit(), "bounce should be true")

        // bounced
        assertEquals(false, reader.loadBit(), "bounced should be false")

        // src: addr_none
        assertEquals(0L, reader.loadUint(2), "src should be addr_none (00)")

        // dest: addr_std$10
        assertEquals(2L, reader.loadUint(2), "dest should be addr_std (10)")
        assertEquals(0L, reader.loadUint(1), "no anycast")
        assertEquals(0L, reader.loadInt(8), "workchain 0")

        // Skip 256-bit hash
        val destHash = reader.loadBuffer(32)
        assertTrue(destHash.size == 32, "dest hash should be 32 bytes")

        // value (coins - varuint with 4-bit header)
        val coinsValue = reader.loadCoins()
        assertEquals(value, coinsValue, "coins should match value")

        // extra currency empty
        assertEquals(false, reader.loadBit(), "no extra currency")

        // ihr_fee
        assertEquals(0L, reader.loadCoins(), "ihr_fee should be 0")

        // fwd_fee
        assertEquals(0L, reader.loadCoins(), "fwd_fee should be 0")

        // created_lt
        assertEquals(0L, reader.loadUint(64), "created_lt should be 0")

        // created_at
        assertEquals(0L, reader.loadUint(32), "created_at should be 0")

        // no init
        assertEquals(false, reader.loadBit(), "no init")

        // body inline
        assertEquals(false, reader.loadBit(), "body should be inline (empty)")
    }

    @Test
    fun shouldStoreMessageWithBodyAsReference() {
        val destAddress = TonAddress.parse("EQDtFpEwcFAEcRe5mLVh2N6C0xZFh1KQRq5QZMYt5-Jpbo8-")
        val largeBody = beginCell()
            .storeUint(0xDEADBEEFL, 32)
            .storeUint(0xCAFEBABEL, 32)
            .storeUint(0x12345678L, 32)
            .storeUint(0x9ABCDEF0L, 32)
            .storeUint(0xDEADBEEFL, 32)
            .storeUint(0xCAFEBABEL, 32)
            .storeUint(0x12345678L, 32)
            .storeUint(0x9ABCDEF0L, 32)
            .storeUint(0xDEADBEEFL, 32)
            .storeUint(0xCAFEBABEL, 32)
            .storeUint(0x12345678L, 32)
            .storeUint(0x9ABCDEF0L, 32)
            .storeUint(0xDEADBEEFL, 32)
            .storeUint(0xCAFEBABEL, 32)
            .storeUint(0x12345678L, 32)
            .storeUint(0x9ABCDEF0L, 32)
            .storeUint(0xDEADBEEFL, 32)
            .storeUint(0xCAFEBABEL, 32)
            .storeUint(0x12345678L, 32)
            .storeUint(0x9ABCDEF0L, 32)
            .storeUint(0xDEADBEEFL, 32)
            .storeUint(0xCAFEBABEL, 32)
            .storeUint(0x12345678L, 32)
            .storeUint(0x9ABCDEF0L, 32)
            .storeUint(0xDEADBEEFL, 32)
            .endCell()

        val msg = internalMessage(to = destAddress, value = 1_000_000L, bounce = true, body = largeBody)
        val cell = beginCell().storeWritable(storeMessageRelaxed(msg)).endCell()

        // Body too large for inline → stored as ref
        assertEquals(1, cell.refs.size, "Body should be stored as a reference")
    }

    @Test
    fun shouldStoreZeroValueMessage() {
        val destAddress = TonAddress.parse("EQDtFpEwcFAEcRe5mLVh2N6C0xZFh1KQRq5QZMYt5-Jpbo8-")
        val msg = internalMessage(to = destAddress, value = 0L, bounce = false)
        val cell = beginCell().storeWritable(storeMessageRelaxed(msg)).endCell()

        val reader = BitReader(cell.bits)
        reader.loadUint(1) // int_msg_info tag
        reader.loadBit()   // ihr_disabled
        val bounce = reader.loadBit()
        assertEquals(false, bounce, "bounce should be false")

        // Skip to value
        reader.loadBit()   // bounced
        reader.loadUint(2) // src addr_none
        reader.loadUint(2) // dest tag
        reader.loadUint(1) // anycast
        reader.loadInt(8)  // workchain
        reader.loadBuffer(32) // hash

        val coins = reader.loadCoins()
        assertEquals(0L, coins, "value should be 0")
    }

    @Test
    fun shouldStoreNonBounceMessage() {
        val destAddress = TonAddress.parse("EQDtFpEwcFAEcRe5mLVh2N6C0xZFh1KQRq5QZMYt5-Jpbo8-")
        val msg = internalMessage(to = destAddress, value = 500_000L, bounce = false)
        val cell = beginCell().storeWritable(storeMessageRelaxed(msg)).endCell()

        val reader = BitReader(cell.bits)
        reader.loadUint(1) // int_msg_info tag
        reader.loadBit()   // ihr_disabled
        val bounce = reader.loadBit()
        assertEquals(false, bounce, "bounce should be false for non-bounce message")
    }

    @Test
    fun messageRelaxedDifferentValuesProduceDifferentCells() {
        val destAddress = TonAddress.parse("EQDtFpEwcFAEcRe5mLVh2N6C0xZFh1KQRq5QZMYt5-Jpbo8-")

        val msg1 = internalMessage(to = destAddress, value = 100L)
        val msg2 = internalMessage(to = destAddress, value = 200L)

        val cell1 = beginCell().storeWritable(storeMessageRelaxed(msg1)).endCell()
        val cell2 = beginCell().storeWritable(storeMessageRelaxed(msg2)).endCell()

        assertTrue(
            !cell1.hash().contentEquals(cell2.hash()),
            "Different values should produce different cells"
        )
    }

    @Test
    fun messageRelaxedDifferentDestinationsProduceDifferentCells() {
        val dest1 = TonAddress.parse("EQDtFpEwcFAEcRe5mLVh2N6C0xZFh1KQRq5QZMYt5-Jpbo8-")
        val dest2 = TonAddress.parse("EQA0i8-CdGnF_DhUHHf92R1ONH6sIA9vLZ_WLcCIhfBBXwtG")

        val msg1 = internalMessage(to = dest1, value = 100L)
        val msg2 = internalMessage(to = dest2, value = 100L)

        val cell1 = beginCell().storeWritable(storeMessageRelaxed(msg1)).endCell()
        val cell2 = beginCell().storeWritable(storeMessageRelaxed(msg2)).endCell()

        assertTrue(
            !cell1.hash().contentEquals(cell2.hash()),
            "Different destinations should produce different cells"
        )
    }

    /**
     * Parses a MessageRelaxed from a Slice, matching the ton-core JS loadMessageRelaxed.
     */
    private fun parseMessageRelaxed(slice: Slice): MessageRelaxed {
        // int_msg_info$0 tag
        slice.loadUint(1)
        slice.loadBit() // ihr_disabled
        val bounce = slice.loadBit()
        slice.loadBit() // bounced
        // src (addr_none)
        slice.loadUint(2)
        // dest (addr_std)
        slice.loadUint(2) // tag
        slice.loadUint(1) // anycast
        val wc = slice.loadInt(8).toInt()
        val hash = slice.loadBuffer(32)
        val dest = TonAddress(wc, hash)
        val value = slice.loadCoins()
        slice.loadBit() // extra currency
        slice.loadCoins() // ihr_fee
        slice.loadCoins() // fwd_fee
        slice.loadUint(64) // created_lt
        slice.loadUint(32) // created_at

        // init
        val hasInit = slice.loadBit()
        if (hasInit) {
            val asRef = slice.loadBit()
            if (asRef) {
                slice.loadRef()
            }
        }

        // body
        val bodyAsRef = slice.loadBit()
        val body = if (bodyAsRef) {
            slice.loadRef()
        } else {
            slice.asCell()
        }

        return MessageRelaxed(dest = dest, value = value, bounce = bounce, body = body)
    }
}
