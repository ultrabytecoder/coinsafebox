package com.ultrabytecoder.coinsafebox.providers.ton.boc

import com.ultrabytecoder.coinsafebox.providers.ton.address.TonAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BitReaderTest {

    private fun seededRandom(seed: String): Random {
        var h = 0
        for (c in seed) h = 31 * h + c.code
        return Random(h)
    }

    @Test
    fun shouldReadUintsFromBuilder() {
        val rng = seededRandom("test-1")
        for (i in 0 until 100) {
            val a = rng.nextLong(0, 281474976710655)
            val b = rng.nextLong(0, 281474976710655)
            val builder = BitBuilder()
            builder.writeUint(a, 48)
            builder.writeUint(b, 48)
            val bits = builder.build()
            val reader = BitReader(bits)
            assertEquals(a, reader.loadUint(48))
            assertEquals(b, reader.loadUint(48))
        }
    }

    @Test
    fun shouldReadIntsFromBuilder() {
        val rng = seededRandom("test-2")
        for (i in 0 until 100) {
            val a = rng.nextLong(-281474976710655, 281474976710655)
            val b = rng.nextLong(-281474976710655, 281474976710655)
            val builder = BitBuilder()
            builder.writeInt(a, 49)
            builder.writeInt(b, 49)
            val bits = builder.build()
            val reader = BitReader(bits)
            assertEquals(a, reader.loadInt(49))
            assertEquals(b, reader.loadInt(49))
        }
    }

    @Test
    fun shouldReadVarUintsFromBuilder() {
        val rng = seededRandom("test-3")
        for (i in 0 until 100) {
            val sizeBits = rng.nextInt(4, 9)
            val a = rng.nextLong(0, 281474976710655)
            val b = rng.nextLong(0, 281474976710655)
            val builder = BitBuilder()
            builder.writeVarUint(a, sizeBits)
            builder.writeVarUint(b, sizeBits)
            val bits = builder.build()
            val reader = BitReader(bits)
            assertEquals(a, reader.loadVarUint(sizeBits))
            assertEquals(b, reader.loadVarUint(sizeBits))
        }
    }

    @Test
    fun shouldReadCoinsFromBuilder() {
        val rng = seededRandom("test-5")
        for (i in 0 until 100) {
            val a = rng.nextLong(0, 281474976710655)
            val b = rng.nextLong(0, 281474976710655)
            val builder = BitBuilder()
            builder.writeCoins(a)
            builder.writeCoins(b)
            val bits = builder.build()
            val reader = BitReader(bits)
            assertEquals(a, reader.loadCoins())
            assertEquals(b, reader.loadCoins())
        }
    }

    @Test
    fun shouldReadAddressFromBuilder() {
        val rng = seededRandom("test-address")
        for (i in 0 until 100) {
            val a: TonAddress? = if (i % 5 == 0) {
                val hash = ByteArray(32); rng.nextBytes(hash)
                TonAddress(if (rng.nextBoolean()) -1 else 0, hash)
            } else null
            val bHash = ByteArray(32); rng.nextBytes(bHash)
            val b = TonAddress(rng.nextInt(0, 2), bHash)

            val builder = BitBuilder()
            builder.writeAddress(a)
            builder.writeAddress(b)
            val bits = builder.build()
            val reader = BitReader(bits)

            val readA = reader.loadMaybeAddress()
            if (a != null) {
                assertEquals(a, readA)
            } else {
                assertNull(readA)
            }
            val readB = reader.loadAddress()
            assertEquals(b, readB)
        }
    }
}
