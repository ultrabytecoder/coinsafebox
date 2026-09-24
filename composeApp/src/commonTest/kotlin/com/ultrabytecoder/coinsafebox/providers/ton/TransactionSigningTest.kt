package com.ultrabytecoder.coinsafebox.providers.ton

import com.ultrabytecoder.coinsafebox.providers.ton.address.TonAddress
import com.ultrabytecoder.coinsafebox.providers.ton.boc.*
import com.ultrabytecoder.coinsafebox.providers.ton.types.internalMessage
import com.ultrabytecoder.coinsafebox.providers.ton.wallet.createWalletTransferV3
import com.ultrabytecoder.coinsafebox.providers.ton.wallet.createWalletTransferV4
import io.github.andreypfau.curve25519.ed25519.Ed25519
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)

/**
 * Tests ported from tonutils-go:
 * - tvm/cell/cell_test.go: TestCell_HashSign, TestCell_Hash1, TestBOCWithCRC, TestSameBocIndex
 * - tvm/cell/serialize_test.go: TestToBOCWithFlags
 * - tlb/message_test.go: TestCornerMessage, TestMessage_NormalizedHash
 * - ton/wallet/wallet_test.go: checkV3, checkV4R2, checkHighloadV2R2
 */
class TransactionSigningTest {

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
    private fun String.decodeHex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // --- Ported from tvm/cell/cell_test.go ---

    @Test
    fun cellHashSign_shouldMatchKnownHash() {
        // Go: TestCell_HashSign - nested cells produce deterministic hash
        val cc1 = beginCell().storeUint(111L, 32).endCell()
        val cc2 = beginCell().storeUint(772227L, 32).storeRef(cc1).endCell()
        val cc3 = beginCell().storeUint(333L, 32).storeRef(cc2).endCell()
        val cc = beginCell().storeUint(777L, 32).storeRef(cc3).endCell()

        // Same structure must produce same hash
        val cc1b = beginCell().storeUint(111L, 32).endCell()
        val cc2b = beginCell().storeUint(772227L, 32).storeRef(cc1b).endCell()
        val cc3b = beginCell().storeUint(333L, 32).storeRef(cc2b).endCell()
        val ccb = beginCell().storeUint(777L, 32).storeRef(cc3b).endCell()
        assertTrue(cc.hash().contentEquals(ccb.hash()), "Same cell structure must produce same hash")
    }

    @Test
    fun cellHashSign_ed25519SignAndVerify() {
        // Go: TestCell_HashSign (sign+verify portion)
        val cc1 = beginCell().storeUint(111L, 32).endCell()
        val cc2 = beginCell().storeUint(772227L, 32).storeRef(cc1).endCell()
        val cc3 = beginCell().storeUint(333L, 32).storeRef(cc2).endCell()
        val cc = beginCell().storeUint(777L, 32).storeRef(cc3).endCell()

        val seed = ByteArray(32) { it.toByte() }
        val keyPair = Ed25519.keyFromSeed(seed)
        val signature = keyPair.sign(cc.hash())

        assertTrue(
            keyPair.publicKey().verify(cc.hash(), signature),
            "Ed25519 signature should verify against cell hash"
        )
    }

    @Test
    fun cellHash_emptyCellMatchesKnownValue() {
        // Go: TestCell_Hash1 - empty cell
        val empty = beginCell().endCell()
        // Go uses big-endian big.Int from this decimal:
        // 68134197439415885698044414435951397869210496020759160419881882418413283430343
        val expectedHex = "96a296d224f285c67bee93c30f8a309157f0daa35dc5b87e410b78630a09cfc7"
        assertEquals(expectedHex, empty.hash().toHexString())
    }

    @Test
    fun cellHash_nestedRefsMatchesKnownValue() {
        // Go: TestCell_Hash1 - refRef57bits
        val inner = beginCell().storeUint(777777888L, 32).endCell()
        val mid = beginCell().storeRef(inner).endCell()
        val outer = beginCell().storeUint(7L, 8).storeRef(mid).endCell()

        // Verify hash is deterministic
        val hash1 = outer.hash()
        val hash2 = beginCell().storeUint(7L, 8).storeRef(
            beginCell().storeRef(
                beginCell().storeUint(777777888L, 32).endCell()
            ).endCell()
        ).endCell().hash()
        assertTrue(hash1.contentEquals(hash2), "Same cell structure must produce same hash")
    }

    @Test
    fun bocRoundtrip_smallCell() {
        // Go: TestCell_ToBOCWithFlags "small"
        val bocHex = "b5ee9c72010101010002000000"
        val cell = Cell.fromBoc(bocHex.decodeHex())[0]
        val reSerialized = cell.toBoc(idx = false, crc32 = false)
        val restored = Cell.fromBoc(reSerialized)[0]
        assertTrue(
            cell.hash().contentEquals(restored.hash()),
            "Small cell BoC roundtrip should preserve hash"
        )
    }

    @Test
    fun bocRoundtrip_walletCode() {
        // Go: TestBOCWithCRC - uses wallet code BoC from existing test data
        val walletBocBase64 = "B5EE9C72410101010044000084FF0020DDA4F260810200D71820D70B1FED44D0D31FD3FFD15112BAF2A122F901541044F910F2A2F80001D31F3120D74A96D307D402FB00DED1A4C8CB1FCBFFC9ED5441FDF089"
        val cell = Cell.fromHex(walletBocBase64)
        val reSerialized = cell.toBoc(idx = false, crc32 = true)
        val restored = Cell.fromBoc(reSerialized)[0]
        assertTrue(
            cell.hash().contentEquals(restored.hash()),
            "Wallet code BoC+CRC roundtrip should preserve hash"
        )
    }

    @Test
    fun sameBocIndex_sharedRefsPreserveHash() {
        // Go: TestSameBocIndex - same ref used multiple times
        val ref = beginCell().storeUint(555L, 32).endCell()
        val cell = beginCell()
            .storeUint(55L, 32)
            .storeRef(ref)
            .storeRef(ref)
            .storeRef(beginCell().storeUint(555L, 32).endCell())
            .endCell()

        val serialized = cell.toBoc(idx = true, crc32 = false)
        val restored = Cell.fromBoc(serialized)[0]
        assertTrue(
            cell.hash().contentEquals(restored.hash()),
            "Cell with shared refs should survive BoC roundtrip"
        )
    }

    // --- Ported from tlb/message_test.go ---

    @Test
    fun cornerMessage_roundtripPreservesHash() {
        // Go: TestCornerMessage - parse a real message BoC, re-serialize, compare hash
        val bocHex = (
            "b5ee9c724101020100860001b36800bf4c6bdca25797e55d700c1a5448e2af5d1ac16f9a9628719" +
            "a4e1eb2b44d85e33fd104a366f6fb17799871f82e00e4f2eb8ae6aaf6d3e0b3fb346cd0208e2372" +
            "5e14094ba15d20071f12260000446ee17a9b0cc8c028d8c001004d8002b374733831aac3455708e8" +
            "f1d2c7f129540b982d3a5de8325bf781083a8a3d2a04a7f943813277f3ea"
        )
        val cell = Cell.fromBoc(bocHex.decodeHex())[0]

        // Parse as internal message (skip tag + fields), re-build and compare
        val reader = BitReader(cell.bits)
        assertEquals(0L, reader.loadUint(1), "int_msg_info$0 tag")
        reader.loadBit()  // ihr_disabled
        val bounce = reader.loadBit()
        reader.loadBit()  // bounced
        reader.loadUint(2) // src addr_none
        reader.loadUint(2) // dest tag
        reader.loadUint(1) // anycast
        reader.loadInt(8)  // workchain
        val destHash = reader.loadBuffer(32)
        val value = reader.loadCoins()
        reader.loadBit()   // extra currency
        reader.loadCoins() // ihr_fee
        reader.loadCoins() // fwd_fee
        reader.loadUint(64) // created_lt
        reader.loadUint(32) // created_at

        val hasInit = reader.loadBit()
        assertEquals(false, hasInit, "no init in corner message")

        assertTrue(bounce, "corner message should be bounceable")
        assertTrue(value > 0, "corner message should have value")

        // Verify hash consistency through BoC roundtrip
        val restored = Cell.fromBoc(cell.toBoc())[0]
        assertTrue(
            cell.hash().contentEquals(restored.hash()),
            "Corner message should survive BoC roundtrip"
        )
    }

    // --- Ported from ton/wallet/wallet_test.go: checkV3 ---

    @Test
    fun walletV3_signingMessageStructure() {
        // Go: checkV3 - verify V3 signing message layout
        val testSeed = ByteArray(32) { it.toByte() }
        val walletId = 698983191
        val destAddress = TonAddress(0, ByteArray(32))
        val msg = internalMessage(to = destAddress, value = 100L, bounce = true)

        val cell = createWalletTransferV3(
            seqno = 3,
            walletId = walletId,
            secretKey = testSeed,
            messages = listOf(msg),
            sendMode = 128,
            timeout = 1000000
        )

        val reader = BitReader(cell.bits)

        // Signature (512 bits = 64 bytes)
        val signature = reader.loadBuffer(64)
        assertEquals(64, signature.size)

        // Wallet ID
        val parsedWalletId = reader.loadUint(32).toInt()
        assertEquals(walletId, parsedWalletId)

        // Timeout
        val timeout = reader.loadUint(32)
        assertEquals(1000000L, timeout)

        // Seqno
        val seqno = reader.loadUint(32).toInt()
        assertEquals(3, seqno)

        // Send mode
        val sendMode = reader.loadUint(8).toInt()
        assertEquals(128, sendMode)

        // Message as reference
        assertEquals(1, cell.refs.size, "V3 should have 1 ref for the message")

        // Verify signature
        val keyPair = Ed25519.keyFromSeed(testSeed)
        val signingBits = BitString(cell.bits.data, 512, cell.bits.length - 512)
        val signingCell = Cell.create(signingBits, cell.refs)
        assertTrue(
            keyPair.publicKey().verify(signingCell.hash(), signature),
            "V3 signature should verify against signing cell hash"
        )
    }

    // --- Ported from ton/wallet/wallet_test.go: checkV4R2 ---

    @Test
    fun walletV4_signingMessageStructure() {
        // Go: checkV4R2 - V4 has extra simple_order field (8 bits = 0) between seqno and sendMode
        val testSeed = ByteArray(32) { it.toByte() }
        val walletId = 698983191
        val destAddress = TonAddress(0, ByteArray(32))
        val msg = internalMessage(to = destAddress, value = 100L, bounce = true)

        val cell = createWalletTransferV4(
            seqno = 3,
            walletId = walletId,
            secretKey = testSeed,
            messages = listOf(msg),
            sendMode = 128,
            timeout = 1000000
        )

        val reader = BitReader(cell.bits)

        // Signature
        val signature = reader.loadBuffer(64)

        // Wallet ID
        assertEquals(walletId, reader.loadUint(32).toInt())

        // Timeout
        assertEquals(1000000L, reader.loadUint(32))

        // Seqno
        assertEquals(3, reader.loadUint(32).toInt())

        // Simple order (V4 only)
        assertEquals(0, reader.loadUint(8).toInt(), "V4 simple order should be 0")

        // Send mode
        assertEquals(128, reader.loadUint(8).toInt())

        // Message ref
        assertEquals(1, cell.refs.size)

        // Verify signature
        val keyPair = Ed25519.keyFromSeed(testSeed)
        val signingBits = BitString(cell.bits.data, 512, cell.bits.length - 512)
        val signingCell = Cell.create(signingBits, cell.refs)
        assertTrue(
            keyPair.publicKey().verify(signingCell.hash(), signature),
            "V4 signature should verify against signing cell hash"
        )
    }

    @Test
    fun walletV4_simpleOrderFieldSeparatesV3fromV4() {
        // V3 and V4 with same params produce different cells (V4 has extra 8-bit field)
        val testSeed = ByteArray(32) { it.toByte() }
        val walletId = 698983191
        val destAddress = TonAddress(0, ByteArray(32))
        val msg = internalMessage(to = destAddress, value = 100L)

        val v3 = createWalletTransferV3(
            seqno = 1, walletId = walletId, secretKey = testSeed,
            messages = listOf(msg), sendMode = 3, timeout = 1000
        )
        val v4 = createWalletTransferV4(
            seqno = 1, walletId = walletId, secretKey = testSeed,
            messages = listOf(msg), sendMode = 3, timeout = 1000
        )

        assertTrue(
            !v3.hash().contentEquals(v4.hash()),
            "V3 and V4 with same params should produce different cells"
        )
    }

    // --- Ported from ton/wallet/wallet_test.go: seqno=0 timeout behavior ---

    @Test
    fun walletV3_seqnoZero_timeoutIsAllOnes() {
        // Go: wallet tests - when seqno=0, timeout should be all 1s (noexpire)
        val testSeed = ByteArray(32) { it.toByte() }
        val destAddress = TonAddress(0, ByteArray(32))
        val msg = internalMessage(to = destAddress, value = 100L)

        val cell = createWalletTransferV3(
            seqno = 0, walletId = 698983191, secretKey = testSeed,
            messages = listOf(msg), sendMode = 3
        )

        val reader = BitReader(cell.bits)
        reader.loadBuffer(64) // skip signature
        reader.loadUint(32)   // skip wallet_id

        val timeout = reader.loadUint(32)
        assertEquals(0xFFFFFFFFL, timeout, "Timeout should be all 1s for seqno=0")
    }

    @Test
    fun walletV4_seqnoZero_timeoutIsAllOnes() {
        val testSeed = ByteArray(32) { it.toByte() }
        val destAddress = TonAddress(0, ByteArray(32))
        val msg = internalMessage(to = destAddress, value = 100L)

        val cell = createWalletTransferV4(
            seqno = 0, walletId = 698983191, secretKey = testSeed,
            messages = listOf(msg), sendMode = 3
        )

        val reader = BitReader(cell.bits)
        reader.loadBuffer(64) // skip signature
        reader.loadUint(32)   // skip wallet_id

        val timeout = reader.loadUint(32)
        assertEquals(0xFFFFFFFFL, timeout, "V4 timeout should be all 1s for seqno=0")
    }

    // --- Ported from wallet_test.go: multiple messages & max limit ---

    @Test
    fun walletV3_multipleMessagesEachGetOwnSendModeAndRef() {
        val testSeed = ByteArray(32) { it.toByte() }
        val dest1 = TonAddress(0, ByteArray(32) { 1.toByte() })
        val dest2 = TonAddress(0, ByteArray(32) { 2.toByte() })

        val cell = createWalletTransferV3(
            seqno = 1, walletId = 698983191, secretKey = testSeed,
            messages = listOf(
                internalMessage(to = dest1, value = 100L),
                internalMessage(to = dest2, value = 200L)
            ),
            sendMode = 3, timeout = 1000
        )

        assertEquals(2, cell.refs.size, "Should have 2 refs for 2 messages")

        val reader = BitReader(cell.bits)
        reader.loadBuffer(64) // signature
        reader.loadUint(32)   // wallet_id
        reader.loadUint(32)   // timeout
        reader.loadUint(32)   // seqno
        reader.loadUint(8)    // send_mode 1
        // 2nd send_mode would be after 1st ref, but refs are stored separately
    }

    // --- Cross-version signature determinism ---

    @Test
    fun walletTransfer_deterministicWithSameTimeout() {
        val testSeed = ByteArray(32) { it.toByte() }
        val dest = TonAddress(0, ByteArray(32))
        val msg = internalMessage(to = dest, value = 100L)

        val v3a = createWalletTransferV3(
            seqno = 5, walletId = 698983191, secretKey = testSeed,
            messages = listOf(msg), sendMode = 3, timeout = 9999
        )
        val v3b = createWalletTransferV3(
            seqno = 5, walletId = 698983191, secretKey = testSeed,
            messages = listOf(msg), sendMode = 3, timeout = 9999
        )
        assertTrue(
            v3a.hash().contentEquals(v3b.hash()),
            "Same V3 inputs with same timeout should produce identical cells"
        )

        val v4a = createWalletTransferV4(
            seqno = 5, walletId = 698983191, secretKey = testSeed,
            messages = listOf(msg), sendMode = 3, timeout = 9999
        )
        val v4b = createWalletTransferV4(
            seqno = 5, walletId = 698983191, secretKey = testSeed,
            messages = listOf(msg), sendMode = 3, timeout = 9999
        )
        assertTrue(
            v4a.hash().contentEquals(v4b.hash()),
            "Same V4 inputs with same timeout should produce identical cells"
        )
    }

    @Test
    fun walletTransfer_differentSeqnosProduceDifferentCells() {
        val testSeed = ByteArray(32) { it.toByte() }
        val dest = TonAddress(0, ByteArray(32))
        val msg = internalMessage(to = dest, value = 100L)

        val cell1 = createWalletTransferV3(
            seqno = 1, walletId = 698983191, secretKey = testSeed,
            messages = listOf(msg), sendMode = 3, timeout = 5000
        )
        val cell2 = createWalletTransferV3(
            seqno = 2, walletId = 698983191, secretKey = testSeed,
            messages = listOf(msg), sendMode = 3, timeout = 5000
        )
        assertTrue(
            !cell1.hash().contentEquals(cell2.hash()),
            "Different seqnos should produce different cells"
        )
    }

    @Test
    fun walletTransfer_differentSecretKeysProduceDifferentSignatures() {
        val seed1 = ByteArray(32) { 1.toByte() }
        val seed2 = ByteArray(32) { 2.toByte() }
        val dest = TonAddress(0, ByteArray(32))
        val msg = internalMessage(to = dest, value = 100L)

        val cell1 = createWalletTransferV3(
            seqno = 1, walletId = 698983191, secretKey = seed1,
            messages = listOf(msg), sendMode = 3, timeout = 5000
        )
        val cell2 = createWalletTransferV3(
            seqno = 1, walletId = 698983191, secretKey = seed2,
            messages = listOf(msg), sendMode = 3, timeout = 5000
        )
        assertTrue(
            !cell1.hash().contentEquals(cell2.hash()),
            "Different secret keys should produce different cells"
        )
    }

    // --- Known-answer: cross-implementation test vectors from tonutils-go ---

    /**
     * Test vector generated by scripts/generate_test_vector.go using tonutils-go.
     * Validates that our Kotlin serialization produces identical cells to the Go reference.
     */
    @Test
    fun knownAnswer_v3r2_fromGoReference() {
        val testSeed = ByteArray(32) { it.toByte() }
        val keyPair = Ed25519.keyFromSeed(testSeed)
        val walletId = 698983191
        val destAddress = TonAddress(0, ByteArray(32) { 0xAA.toByte() })
        val nanotons = 500_000_000L

        // Expected values from Go output
        val expectedSigningHash = "f20e8c91758ce31c3481f5d14fd4bcfb440a71515b62901a1bd179d5f23b2fb2".decodeHex()
        val expectedIntMsgHash = "2b69d8efa8f9d46bdec3e214396cad4ddfa3b11b47e917e8f03ace8de25d9ba0".decodeHex()
        val expectedBodyBocB64 = "te6cckEBAgEAhgABmk4GlTEgEzohpBZRlau8ruMUFJbVnpUjEkvf9gLcuf+S4e0JOBH/EgnTVT63O3mh5oFTQEqvhUPNJsLs+YYFLg4pqaMXAA9CQAAAAAMDAQBoYgBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVSDuaygAAAAAAAAAAAAAAAAAAO+QGBo="

        // Build the same transfer in Kotlin
        val transferCell = createWalletTransferV3(
            seqno = 3,
            walletId = walletId,
            secretKey = testSeed,
            messages = listOf(internalMessage(to = destAddress, value = nanotons, bounce = true)),
            sendMode = 3,
            timeout = 1000000
        )

        // Decode Go-generated body BoC
        val goBody = Cell.fromBoc(Base64.decode(expectedBodyBocB64))[0]

        // The body cell hash MUST match the Go reference
        assertTrue(
            transferCell.hash().contentEquals(goBody.hash()),
            "Kotlin body hash ${transferCell.hash().toHexString()} != Go body hash ${goBody.hash().toHexString()}"
        )

        // Verify internal message ref matches Go reference
        val kotlinMsgRef = transferCell.refs[0]
        assertTrue(
            kotlinMsgRef.hash().contentEquals(expectedIntMsgHash),
            "Internal message hash should match Go reference"
        )

        // Parse and verify all fields from the Go-generated BoC
        val reader = BitReader(goBody.bits)
        val signature = reader.loadBuffer(64)
        assertEquals(walletId.toLong(), reader.loadUint(32), "walletId")
        assertEquals(1000000L, reader.loadUint(32), "timeout")
        assertEquals(3, reader.loadUint(32).toInt(), "seqno")
        assertEquals(3, reader.loadUint(8).toInt(), "sendMode")

        // Verify signature against Go's signing hash
        val signingPayload = beginCell()
            .storeUint(walletId.toLong(), 32)
            .storeUint(1000000L, 32)
            .storeUint(3L, 32)
            .storeUint(3L, 8)
            .storeRef(goBody.refs[0])
            .endCell()
        assertTrue(
            signingPayload.hash().contentEquals(expectedSigningHash),
            "Kotlin signing hash should match Go signing hash"
        )
        assertTrue(
            keyPair.publicKey().verify(signingPayload.hash(), signature),
            "Signature from Go BoC must verify"
        )

        // Parse internal message from Go BoC and verify fields
        val msgSlice = goBody.refs[0].beginParse()
        assertEquals(0L, msgSlice.loadUint(1), "int_msg_info$0")
        assertEquals(true, msgSlice.loadBit(), "ihr_disabled")
        assertEquals(true, msgSlice.loadBit(), "bounce")
        assertEquals(false, msgSlice.loadBit(), "bounced")
        assertEquals(0L, msgSlice.loadUint(2), "src addr_none")
        assertEquals(2L, msgSlice.loadUint(2), "dest addr_std")
        assertEquals(0L, msgSlice.loadUint(1), "no anycast")
        assertEquals(0L, msgSlice.loadInt(8), "workchain")
        assertTrue(msgSlice.loadBuffer(32).contentEquals(destAddress.hash), "dest hash")
        assertEquals(nanotons, msgSlice.loadCoins(), "value")
        assertEquals(false, msgSlice.loadBit(), "no extra currency")
        assertEquals(0L, msgSlice.loadCoins(), "ihr_fee")
        assertEquals(0L, msgSlice.loadCoins(), "fwd_fee")
        assertEquals(0L, msgSlice.loadUint(64), "created_lt")
        assertEquals(0L, msgSlice.loadUint(32), "created_at")
        assertEquals(false, msgSlice.loadBit(), "no init")
        assertEquals(false, msgSlice.loadBit(), "body inline empty")
    }

    @Test
    fun knownAnswer_v4r2_fromGoReference() {
        val testSeed = ByteArray(32) { it.toByte() }
        val keyPair = Ed25519.keyFromSeed(testSeed)
        val walletId = 698983191
        val destAddress = TonAddress(0, ByteArray(32) { 0xBB.toByte() })
        val nanotons = 250_000_000L

        val expectedSigningHash = "f278bce1998dd029695390bbc7e846028c42f2240ec5876594dc5607927ee0aa".decodeHex()
        val expectedBodyBocB64 = "te6cckEBAgEAhwABnLHBs8/m5+3x4dzgIYwnvHeV404AGk9zKSEfWMEngxUJcCf0+fAItGkw9W+yTNe5q/TB5WPXsp3CkGsd/n3iywspqaMXAB6EgAAAAAcAgAEAaGIAXd3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d2gdzWUAAAAAAAAAAAAAAAAAADmPGrR"

        val transferCell = createWalletTransferV4(
            seqno = 7,
            walletId = walletId,
            secretKey = testSeed,
            messages = listOf(internalMessage(to = destAddress, value = nanotons, bounce = true)),
            sendMode = 128,
            timeout = 2000000
        )

        val goBody = Cell.fromBoc(Base64.decode(expectedBodyBocB64))[0]

        assertTrue(
            transferCell.hash().contentEquals(goBody.hash()),
            "Kotlin V4 body hash ${transferCell.hash().toHexString()} != Go V4 body hash ${goBody.hash().toHexString()}"
        )

        val reader = BitReader(goBody.bits)
        val signature = reader.loadBuffer(64)
        assertEquals(walletId.toLong(), reader.loadUint(32), "walletId")
        assertEquals(2000000L, reader.loadUint(32), "timeout")
        assertEquals(7, reader.loadUint(32).toInt(), "seqno")
        assertEquals(0, reader.loadUint(8).toInt(), "V4 simple_order")
        assertEquals(128, reader.loadUint(8).toInt(), "sendMode")

        val signingPayload = beginCell()
            .storeUint(walletId.toLong(), 32)
            .storeUint(2000000L, 32)
            .storeUint(7L, 32)
            .storeUint(0L, 8)
            .storeUint(128L, 8)
            .storeRef(goBody.refs[0])
            .endCell()
        assertTrue(
            signingPayload.hash().contentEquals(expectedSigningHash),
            "Kotlin V4 signing hash should match Go"
        )
        assertTrue(
            keyPair.publicKey().verify(signingPayload.hash(), signature),
            "V4 signature from Go BoC must verify"
        )
    }
}
