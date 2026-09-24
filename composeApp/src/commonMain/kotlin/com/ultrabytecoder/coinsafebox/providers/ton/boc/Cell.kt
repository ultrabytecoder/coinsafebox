package com.ultrabytecoder.coinsafebox.providers.ton.boc

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

class Cell private constructor(
    val type: CellType,
    val bits: BitString,
    val refs: List<Cell>,
    val mask: LevelMask,
    private val _hashes: List<ByteArray>,
    private val _depths: List<Int>
) {
    fun hash(level: Int = 3): ByteArray = _hashes[minOf(_hashes.size - 1, level)]
    fun depth(level: Int = 3): Int = _depths[minOf(_depths.size - 1, level)]

    fun beginParse(allowExotic: Boolean = false): Slice {
        if (type != CellType.ORDINARY && !allowExotic) {
            throw IllegalStateException("Exotic cells cannot be parsed")
        }
        return Slice(BitReader(bits), refs.toMutableList())
    }

    fun toBoc(idx: Boolean = false, crc32: Boolean = true): ByteArray =
        BocSerialization.serializeBoc(this, idx, crc32)

    companion object {
        private val hasher = CryptographyProvider.Default.get(SHA256).hasher()

        val EMPTY: Cell = create(BitString.EMPTY, emptyList())

        fun create(bits: BitString, refs: List<Cell> = emptyList()): Cell {
            require(refs.size <= 4) { "Invalid number of references: ${refs.size}" }
            require(bits.length <= 1023) { "Bits overflow: ${bits.length} > 1023" }

            val mask = run {
                var m = 0
                for (r in refs) m = m or r.mask.value
                LevelMask(m)
            }

            val wonders = wonderCalculator(CellType.ORDINARY, bits, refs, mask, null)
            return Cell(CellType.ORDINARY, bits, refs.toList(), wonders.mask, wonders.hashes, wonders.depths)
        }

        fun createExotic(bits: BitString, refs: List<Cell> = emptyList()): Cell {
            require(refs.size <= 4) { "Invalid number of references: ${refs.size}" }
            require(bits.length <= 1023) { "Bits overflow: ${bits.length} > 1023" }

            val typeReader = BitReader(bits)
            val typeValue = typeReader.loadUint(8).toInt()
            val type = when (typeValue) {
                1 -> CellType.PRUNED_BRANCH
                2 -> CellType.LIBRARY
                3 -> CellType.MERKLE_PROOF
                4 -> CellType.MERKLE_UPDATE
                else -> throw IllegalArgumentException("Invalid exotic cell type: $typeValue")
            }

            val resolved = resolveExotic(type, bits, refs)
            val wonders = wonderCalculator(type, bits, refs, resolved.mask, resolved.pruned)
            return Cell(type, bits, refs.toList(), wonders.mask, wonders.hashes, wonders.depths)
        }

        fun fromBoc(bytes: ByteArray): List<Cell> = BocSerialization.deserializeBoc(bytes)

        fun fromHex(hex: String): Cell {
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cells = fromBoc(bytes)
            require(cells.size == 1) { "Expected 1 cell, got ${cells.size}" }
            return cells[0]
        }

        private data class ResolvedExotic(
            val mask: LevelMask,
            val pruned: List<Pair<ByteArray, Int>>? = null
        )

        private data class WonderResult(
            val mask: LevelMask,
            val hashes: List<ByteArray>,
            val depths: List<Int>
        )

        private fun resolveExotic(type: CellType, bits: BitString, refs: List<Cell>): ResolvedExotic {
            return when (type) {
                CellType.PRUNED_BRANCH -> {
                    val reader = BitReader(bits)
                    reader.loadUint(8) // type
                    val levelMask = if (bits.length == 280) {
                        LevelMask(1)
                    } else {
                        LevelMask(reader.loadUint(8).toInt())
                    }
                    val prunedCount = levelMask.level
                    val hashes = (0 until prunedCount).map { reader.loadBuffer(32) }
                    val depths = (0 until prunedCount).map { reader.loadUint(16).toInt() }
                    ResolvedExotic(levelMask, hashes.zip(depths))
                }
                CellType.LIBRARY -> ResolvedExotic(LevelMask(0))
                CellType.MERKLE_PROOF -> ResolvedExotic(LevelMask(refs[0].mask.value shr 1))
                CellType.MERKLE_UPDATE -> ResolvedExotic(LevelMask((refs[0].mask.value or refs[1].mask.value) shr 1))
                else -> throw IllegalArgumentException("Unsupported exotic type")
            }
        }

        private fun wonderCalculator(
            type: CellType,
            bits: BitString,
            refs: List<Cell>,
            levelMask: LevelMask,
            pruned: List<Pair<ByteArray, Int>>?
        ): WonderResult {
            val hashCount = if (type == CellType.PRUNED_BRANCH) 1 else levelMask.hashCount
            val totalHashCount = levelMask.hashCount
            val hashIOffset = totalHashCount - hashCount

            val depths = mutableListOf<Int>()
            val hashes = mutableListOf<ByteArray>()

            var hashI = 0
            for (levelI in 0..levelMask.level) {
                if (!levelMask.isSignificant(levelI)) continue

                if (hashI < hashIOffset) {
                    hashI++
                    continue
                }

                val currentBits: BitString = if (hashI == hashIOffset) {
                    bits
                } else {
                    BitString(hashes[hashI - hashIOffset - 1], 0, 256)
                }

                var currentDepth = 0
                for (c in refs) {
                    val childDepth = if (type == CellType.MERKLE_PROOF || type == CellType.MERKLE_UPDATE) {
                        c.depth(levelI + 1)
                    } else {
                        c.depth(levelI)
                    }
                    currentDepth = maxOf(currentDepth, childDepth)
                }
                if (refs.isNotEmpty()) currentDepth++

                val repr = getRepr(bits, currentBits, refs, levelI, type)
                val hash = hasher.hashBlocking(repr)

                depths.add(currentDepth)
                hashes.add(hash)
                hashI++
            }

            val resolvedHashes = mutableListOf<ByteArray>()
            val resolvedDepths = mutableListOf<Int>()

            if (pruned != null && type == CellType.PRUNED_BRANCH) {
                for (i in 0 until 4) {
                    val hashIndex = levelMask.apply(i).hashIndex
                    val thisHashIndex = levelMask.hashIndex
                    if (hashIndex != thisHashIndex && hashIndex < pruned.size) {
                        resolvedHashes.add(pruned[hashIndex].first)
                        resolvedDepths.add(pruned[hashIndex].second)
                    } else {
                        resolvedHashes.add(hashes[0])
                        resolvedDepths.add(depths[0])
                    }
                }
            } else {
                for (i in 0 until 4) {
                    val idx = levelMask.apply(i).hashIndex
                    resolvedHashes.add(hashes[idx])
                    resolvedDepths.add(depths[idx])
                }
            }

            return WonderResult(levelMask, resolvedHashes, resolvedDepths)
        }
    }
}
