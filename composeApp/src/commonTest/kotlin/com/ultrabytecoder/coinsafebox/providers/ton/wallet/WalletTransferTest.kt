package com.ultrabytecoder.coinsafebox.providers.ton.wallet

import com.ultrabytecoder.coinsafebox.providers.ton.address.TonAddress
import com.ultrabytecoder.coinsafebox.providers.ton.boc.*
import com.ultrabytecoder.coinsafebox.providers.ton.types.internalMessage
import io.github.andreypfau.curve25519.ed25519.Ed25519
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalletTransferTest {

    private val testSeed = ByteArray(32) { it.toByte() }
    private val testPublicKey = Ed25519.keyFromSeed(testSeed).publicKey().toByteArray()
    private val walletId = 698983191

    @Test
    fun shouldCreateV3R2TransferWithCorrectStructure() {
        val destAddress = TonAddress(0, ByteArray(32) { it.toByte() })
        val msg = internalMessage(to = destAddress, value = 100_000_000L)

        val cell = createWalletTransferV3(
            seqno = 1,
            walletId = walletId,
            secretKey = testSeed,
            messages = listOf(msg),
            sendMode = 3
        )

        // The cell should contain: signature (512 bits) + signing message
        // signing message: walletId (32) + timeout (32) + seqno (32) + sendMode (8) + ref to message
        val reader = BitReader(cell.bits)

        // First 512 bits = signature
        val signature = reader.loadBuffer(64)
        assertEquals(64, signature.size, "Signature should be 64 bytes (512 bits)")

        // Wallet ID
        val parsedWalletId = reader.loadUint(32).toInt()
        assertEquals(walletId, parsedWalletId, "wallet_id should match")

        // Timeout (non-zero for seqno > 0)
        val timeout = reader.loadUint(32)
        assertTrue(timeout > 0, "Timeout should be non-zero for seqno > 0")

        // Seqno
        val parsedSeqno = reader.loadUint(32).toInt()
        assertEquals(1, parsedSeqno, "seqno should be 1")

        // Send mode
        val parsedSendMode = reader.loadUint(8).toInt()
        assertEquals(3, parsedSendMode, "send_mode should be 3")

        // Message should be stored as a reference
        assertEquals(1, cell.refs.size, "Should have 1 ref (the message)")
    }

    @Test
    fun shouldCreateV3R2TransferWithSeqnoZeroUsesAllOnesTimeout() {
        val destAddress = TonAddress(0, ByteArray(32))
        val msg = internalMessage(to = destAddress, value = 100L)

        val cell = createWalletTransferV3(
            seqno = 0,
            walletId = walletId,
            secretKey = testSeed,
            messages = listOf(msg),
            sendMode = 3
        )

        val reader = BitReader(cell.bits)
        reader.loadBuffer(64) // signature
        reader.loadUint(32)   // wallet_id

        // For seqno=0, timeout should be 32 bits of all 1s
        val timeout = reader.loadUint(32)
        assertEquals(0xFFFFFFFFL, timeout, "Timeout should be all 1s for seqno=0")

        val seqno = reader.loadUint(32).toInt()
        assertEquals(0, seqno, "seqno should be 0")
    }

    @Test
    fun shouldSignV3R2TransferWithCorrectKey() {
        val destAddress = TonAddress(0, ByteArray(32))
        val msg = internalMessage(to = destAddress, value = 100L)

        val cell = createWalletTransferV3(
            seqno = 1,
            walletId = walletId,
            secretKey = testSeed,
            messages = listOf(msg),
            sendMode = 3
        )

        // Extract signature and verify it
        val reader = BitReader(cell.bits)
        val signature = reader.loadBuffer(64)

        // The rest is the signing message cell content
        val signingBits = BitString(cell.bits.data, 512, cell.bits.length - 512)
        val signingCell = Cell.create(signingBits, cell.refs)

        val key = Ed25519.keyFromSeed(testSeed)
        val publicKey = key.publicKey()
        assertTrue(publicKey.verify(signingCell.hash(), signature), "Signature should verify against the signing cell hash")
    }

    @Test
    fun shouldSupportMultipleMessagesInSingleTransfer() {
        val dest1 = TonAddress(0, ByteArray(32) { 1.toByte() })
        val dest2 = TonAddress(0, ByteArray(32) { 2.toByte() })

        val cell = createWalletTransferV3(
            seqno = 1,
            walletId = walletId,
            secretKey = testSeed,
            messages = listOf(
                internalMessage(to = dest1, value = 100L),
                internalMessage(to = dest2, value = 200L)
            ),
            sendMode = 3
        )

        // Should have 2 refs (one per message)
        assertEquals(2, cell.refs.size, "Should have 2 refs for 2 messages")
    }

    @Test
    fun shouldRejectMoreThan4Messages() {
        val dest = TonAddress(0, ByteArray(32))
        val messages = (1..5).map { internalMessage(to = dest, value = it.toLong()) }

        try {
            createWalletTransferV3(
                seqno = 1,
                walletId = walletId,
                secretKey = testSeed,
                messages = messages,
                sendMode = 3
            )
            assertTrue(false, "Should have thrown for >4 messages")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("4") == true, "Error should mention max 4 messages")
        }
    }

    @Test
    fun shouldCreateV3R2TransferDeterministically() {
        val destAddress = TonAddress(0, ByteArray(32))

        val cell1 = createWalletTransferV3(
            seqno = 1,
            walletId = walletId,
            secretKey = testSeed,
            messages = listOf(internalMessage(to = destAddress, value = 100L)),
            sendMode = 3,
            timeout = 1000
        )

        val cell2 = createWalletTransferV3(
            seqno = 1,
            walletId = walletId,
            secretKey = testSeed,
            messages = listOf(internalMessage(to = destAddress, value = 100L)),
            sendMode = 3,
            timeout = 1000
        )

        assertTrue(
            cell1.hash().contentEquals(cell2.hash()),
            "Same inputs with same timeout should produce identical cells"
        )
    }

    @Test
    fun v4TransferShouldIncludeSimpleOrderField() {
        val destAddress = TonAddress(0, ByteArray(32))
        val msg = internalMessage(to = destAddress, value = 100L)

        val cell = createWalletTransferV4(
            seqno = 1,
            walletId = walletId,
            secretKey = testSeed,
            messages = listOf(msg),
            sendMode = 3
        )

        val reader = BitReader(cell.bits)
        reader.loadBuffer(64) // signature
        reader.loadUint(32)   // wallet_id
        reader.loadUint(32)   // timeout
        reader.loadUint(32)   // seqno

        // V4 has an extra "simple order" field = 0 (8 bits)
        val simpleOrder = reader.loadUint(8).toInt()
        assertEquals(0, simpleOrder, "V4 should have simple order = 0")
    }

    @Test
    fun walletTransferRoundtripThroughBoC() {
        val destAddress = TonAddress(0, ByteArray(32) { 0x42.toByte() })
        val msg = internalMessage(to = destAddress, value = 1_000_000_000L)

        val cell = createWalletTransferV3(
            seqno = 5,
            walletId = walletId,
            secretKey = testSeed,
            messages = listOf(msg),
            sendMode = 3
        )

        val bocBytes = cell.toBoc()
        val restored = Cell.fromBoc(bocBytes)[0]

        assertTrue(
            cell.hash().contentEquals(restored.hash()),
            "Wallet transfer cell should survive BoC roundtrip"
        )
    }
}
