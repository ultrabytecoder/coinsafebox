package com.ultrabytecoder.coinsafebox.providers.ton.address

import com.ultrabytecoder.coinsafebox.providers.ton.boc.beginCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TonAddressTest {

    @Test
    fun shouldParseAddressesInVariousForms() {
        val friendly1 = TonAddress.parseFriendly("0QAs9VlT6S776tq3unJcP5Ogsj-ELLunLXuOb1EKcOQi4-QO")
        val friendly2 = TonAddress.parseFriendly("kQAs9VlT6S776tq3unJcP5Ogsj-ELLunLXuOb1EKcOQi47nL")
        val raw = TonAddress.parseRaw("0:2cf55953e92efbeadab7ba725c3f93a0b23f842cbba72d7b8e6f510a70e422e3")

        assertEquals(0, friendly1.workChain)
        assertEquals(0, friendly2.workChain)
        assertEquals(0, raw.workChain)

        val expectedHex = "2cf55953e92efbeadab7ba725c3f93a0b23f842cbba72d7b8e6f510a70e422e3"
        assertEquals(expectedHex, friendly1.hash.toHexString())
        assertEquals(expectedHex, friendly2.hash.toHexString())
        assertEquals(expectedHex, raw.hash.toHexString())

        assertEquals("0:2cf55953e92efbeadab7ba725c3f93a0b23f842cbba72d7b8e6f510a70e422e3", friendly1.toRawString())
        assertEquals("0:2cf55953e92efbeadab7ba725c3f93a0b23f842cbba72d7b8e6f510a70e422e3", friendly2.toRawString())
        assertEquals("0:2cf55953e92efbeadab7ba725c3f93a0b23f842cbba72d7b8e6f510a70e422e3", raw.toRawString())

        val addr4 = TonAddress.parseRaw("-1:3333333333333333333333333333333333333333333333333333333333333333")
        assertEquals(-1, addr4.workChain)
        assertEquals("3333333333333333333333333333333333333333333333333333333333333333", addr4.hash.toHexString())
    }

    @Test
    fun shouldSerializeToFriendlyForm() {
        val address = TonAddress.parseRaw("0:2cf55953e92efbeadab7ba725c3f93a0b23f842cbba72d7b8e6f510a70e422e3")

        // Bounceable
        assertEquals("EQAs9VlT6S776tq3unJcP5Ogsj-ELLunLXuOb1EKcOQi4wJB", address.toString())
        assertEquals("kQAs9VlT6S776tq3unJcP5Ogsj-ELLunLXuOb1EKcOQi47nL", address.toString(testOnly = true))
        assertEquals("EQAs9VlT6S776tq3unJcP5Ogsj+ELLunLXuOb1EKcOQi4wJB", address.toString(urlSafe = false))
        assertEquals("kQAs9VlT6S776tq3unJcP5Ogsj+ELLunLXuOb1EKcOQi47nL", address.toString(testOnly = true, urlSafe = false))

        // Non-Bounceable
        assertEquals("UQAs9VlT6S776tq3unJcP5Ogsj-ELLunLXuOb1EKcOQi41-E", address.toString(bounceable = false))
        assertEquals("0QAs9VlT6S776tq3unJcP5Ogsj-ELLunLXuOb1EKcOQi4-QO", address.toString(bounceable = false, testOnly = true))
        assertEquals("UQAs9VlT6S776tq3unJcP5Ogsj+ELLunLXuOb1EKcOQi41+E", address.toString(bounceable = false, urlSafe = false))
        assertEquals("0QAs9VlT6S776tq3unJcP5Ogsj+ELLunLXuOb1EKcOQi4+QO", address.toString(bounceable = false, urlSafe = false, testOnly = true))
    }

    @Test
    fun shouldImplementEquals() {
        val address1 = TonAddress.parseRaw("0:2cf55953e92efbeadab7ba725c3f93a0b23f842cbba72d7b8e6f510a70e422e3")
        val address2 = TonAddress.parseRaw("0:2cf55953e92efbeadab7ba725c3f93a0b23f842cbba72d7b8e6f510a70e422e3")
        val address3 = TonAddress.parseRaw("-1:2cf55953e92efbeadab7ba725c3f93a0b23f842cbba72d7b8e6f510a70e422e3")
        val address4 = TonAddress.parseRaw("0:2cf55953e92efbeadab7ba725c3f93a0b23f842cbba72d7b8e6f510a70e422e5")

        assertEquals(address1, address2)
        assertEquals(address2, address1)
        assertFalse(address2 == address4)
        assertFalse(address2 == address3)
        assertFalse(address4 == address3)
    }

    @Test
    fun shouldThrowIfAddressIsInvalid() {
        // Hash too short (31 bytes = 62 hex chars)
        var threw = false
        try { TonAddress.parseRaw("0:2cf55953e92efbeadab7ba725c3f93a0b23f842cbba72d7b8e6f510a70e422") }
        catch (e: Exception) { threw = true; assertTrue(e.message?.contains("Invalid address hash length") == true) }
        assertTrue(threw)

        // Hash too short (63 hex chars = floor to 31 bytes)
        threw = false
        try { TonAddress.parseRaw("0:2cf55953e92efbeadab7ba725c3f93a0b23f842cbba72d7b8e6f510a70e422e") }
        catch (e: Exception) { threw = true }
        assertTrue(threw)

        // Unknown type
        threw = false
        try { TonAddress.parse("ton://EQAs9VlT6S776tq3unJcP5Ogsj-ELLunLXuOb1EKcOQi4wJB") }
        catch (e: Exception) { threw = true; assertTrue(e.message?.contains("Unknown address type") == true) }
        assertTrue(threw)

        // Too short for friendly (47 chars instead of 48)
        threw = false
        try { TonAddress.parse("EQAs9VlT6S776tq3unJcP5Ogsj-ELLunLXuOb1EKcOQi4wJ") }
        catch (e: Exception) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun contractAddressShouldResolveCorrectly() {
        val code = beginCell().storeUint(1, 8).endCell()
        val data = beginCell().storeUint(2, 8).endCell()
        val addr = contractAddress(0, code, data)
        val expected = TonAddress.parse("EQCSY_vTjwGrlvTvkfwhinJ60T2oiwgGn3U7Tpw24kupIhHz")
        assertEquals(expected, addr)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
