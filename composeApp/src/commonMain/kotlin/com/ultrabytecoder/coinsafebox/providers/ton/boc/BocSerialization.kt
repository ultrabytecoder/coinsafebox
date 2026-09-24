package com.ultrabytecoder.coinsafebox.providers.ton.boc

object BocSerialization {

    fun deserializeBoc(src: ByteArray): List<Cell> {
        val reader = BitReader(BitString(src, 0, src.size * 8))
        val magic = reader.loadUint(32)

        val boc = when (magic) {
            0x68FF65F3L -> parseOldBoc(reader)
            0xACC3A728L -> parseNewBoc(reader, src)
            0xB5EE9C72L -> parseNewestBoc(reader, src)
            else -> throw IllegalArgumentException("Invalid BOC magic: ${magic.toString(16)}")
        }

        val cellReader = BitReader(BitString(boc.cellData, 0, boc.cellData.size * 8))
        val cells = mutableListOf<CellData>()
        for (i in 0 until boc.cells) {
            cells.add(readCell(cellReader, boc.size))
        }

        val built = arrayOfNulls<Cell>(boc.cells)
        for (i in (boc.cells - 1) downTo 0) {
            val refs = cells[i].refs.map { r ->
                built[r] ?: throw IllegalStateException("Invalid BOC: unresolved ref")
            }
            built[i] = if (cells[i].exotic) {
                Cell.createExotic(cells[i].bits, refs)
            } else {
                Cell.create(cells[i].bits, refs)
            }
        }

        return boc.rootIndices.map { built[it]!! }
    }

    fun serializeBoc(root: Cell, idx: Boolean = false, crc32: Boolean = true): ByteArray {
        val allCells = topologicalSort(root)
        val cellsNum = allCells.size
        val sizeBytes = maxOf((32 - cellsNum.countLeadingZeroBits() + 7) / 8, 1)
        val totalCellSize = allCells.sumOf { calcCellSize(it.first, sizeBytes).toLong() }.toInt()
        val offsetBytes = maxOf((32 - totalCellSize.countLeadingZeroBits() + 7) / 8, 1)

        val indexValues = mutableListOf<Int>()
        var acc = 0
        for (c in allCells) {
            indexValues.add(acc)
            acc += calcCellSize(c.first, sizeBytes)
        }

        val totalSizeBits = (
            4 * 8 +
            8 +
            8 +
            3 * sizeBytes * 8 +
            offsetBytes * 8 +
            sizeBytes * 8 +
            (if (idx) cellsNum * offsetBytes * 8 else 0) +
            totalCellSize * 8 +
            (if (crc32) 4 * 8 else 0)
        )

        val builder = BitBuilder(totalSizeBits)
        builder.writeUint(0xB5EE9C72L, 32)
        builder.writeBit(idx)
        builder.writeBit(crc32)
        builder.writeBit(false)
        builder.writeUint(0, 2)
        builder.writeUint(sizeBytes.toLong(), 3)
        builder.writeUint(offsetBytes.toLong(), 8)
        builder.writeUint(cellsNum.toLong(), sizeBytes * 8)
        builder.writeUint(1, sizeBytes * 8)
        builder.writeUint(0, sizeBytes * 8)
        builder.writeUint(totalCellSize.toLong(), offsetBytes * 8)
        builder.writeUint(0, sizeBytes * 8)

        if (idx) {
            for (i in 0 until cellsNum) {
                builder.writeUint(indexValues[i].toLong(), offsetBytes * 8)
            }
        }

        for (i in 0 until cellsNum) {
            writeCellToBuilder(allCells[i].first, allCells[i].second, sizeBytes, builder)
        }

        if (crc32) {
            val buf = builder.buffer()
            builder.writeBuffer(crc32c(buf))
        }

        return builder.buffer()
    }

    private data class BocParsed(
        val size: Int,
        val cells: Int,
        val rootIndices: List<Int>,
        val cellData: ByteArray
    )

    private data class CellData(
        val bits: BitString,
        val refs: List<Int>,
        val exotic: Boolean
    )

    private fun parseOldBoc(reader: BitReader): BocParsed {
        val size = reader.loadUint(8).toInt()
        val offBytes = reader.loadUint(8).toInt()
        val cells = reader.loadUint(size * 8).toInt()
        val roots = reader.loadUint(size * 8).toInt()
        reader.loadUint(size * 8) // absent
        val totalCellSize = reader.loadUint(offBytes * 8).toInt()
        reader.loadBuffer(cells * offBytes) // index
        val cellData = reader.loadBuffer(totalCellSize)
        return BocParsed(size, cells, listOf(0), cellData)
    }

    private fun parseNewBoc(reader: BitReader, src: ByteArray): BocParsed {
        val size = reader.loadUint(8).toInt()
        val offBytes = reader.loadUint(8).toInt()
        val cells = reader.loadUint(size * 8).toInt()
        val roots = reader.loadUint(size * 8).toInt()
        reader.loadUint(size * 8) // absent
        val totalCellSize = reader.loadUint(offBytes * 8).toInt()
        reader.loadBuffer(cells * offBytes) // index
        val cellData = reader.loadBuffer(totalCellSize)
        val crc = reader.loadBuffer(4)
        val expected = crc32c(src.copyOfRange(0, src.size - 4))
        require(crc.contentEquals(expected)) { "Invalid CRC32C" }
        return BocParsed(size, cells, listOf(0), cellData)
    }

    private fun parseNewestBoc(reader: BitReader, src: ByteArray): BocParsed {
        val hasIdx = reader.loadUint(1).toInt() != 0
        val hasCrc32c = reader.loadUint(1).toInt() != 0
        val hasCacheBits = reader.loadUint(1).toInt() != 0
        reader.loadUint(2) // flags
        val size = reader.loadUint(3).toInt()
        val offBytes = reader.loadUint(8).toInt()
        val cells = reader.loadUint(size * 8).toInt()
        val roots = reader.loadUint(size * 8).toInt()
        reader.loadUint(size * 8) // absent
        val totalCellSize = reader.loadUint(offBytes * 8).toInt()
        val rootIndices = (0 until roots).map { reader.loadUint(size * 8).toInt() }
        if (hasIdx) {
            reader.loadBuffer(cells * offBytes)
        }
        val cellData = reader.loadBuffer(totalCellSize)
        if (hasCrc32c) {
            val crc = reader.loadBuffer(4)
            val expected = crc32c(src.copyOfRange(0, src.size - 4))
            require(crc.contentEquals(expected)) { "Invalid CRC32C" }
        }
        return BocParsed(size, cells, rootIndices, cellData)
    }

    private fun readCell(reader: BitReader, sizeBytes: Int): CellData {
        val d1 = reader.loadUint(8).toInt()
        val refsCount = d1 and 0x07
        val exotic = (d1 and 0x08) != 0
        val d2 = reader.loadUint(8).toInt()
        val dataByteSize = (d2 + 1) / 2
        val paddingAdded = d2 % 2 != 0

        val levelMask = d1 shr 5
        val hasHashes = (d1 and 0x10) != 0
        val hashesCount = getHashesCount(levelMask)
        if (hasHashes) {
            reader.skip(hashesCount * 32 * 8)
            reader.skip(hashesCount * 2 * 8)
        }

        val bits = if (dataByteSize > 0) {
            if (paddingAdded) reader.loadPaddedBits(dataByteSize * 8)
            else reader.loadBits(dataByteSize * 8)
        } else {
            BitString.EMPTY
        }

        val refs = (0 until refsCount).map { reader.loadUint(sizeBytes * 8).toInt() }
        return CellData(bits, refs, exotic)
    }

    private fun getHashesCount(levelMask: Int): Int {
        var mask = levelMask and 7
        var n = 0
        for (i in 0 until 3) {
            n += mask and 1
            mask = mask shr 1
        }
        return n + 1
    }

    private fun calcCellSize(cell: Cell, sizeBytes: Int): Int {
        return 2 + (cell.bits.length + 7) / 8 + cell.refs.size * sizeBytes
    }

    private fun writeCellToBuilder(cell: Cell, refIndices: List<Int>, sizeBytes: Int, builder: BitBuilder) {
        val d1 = getRefsDescriptor(cell.refs, 0, cell.type)
        val d2 = getBitsDescriptor(cell.bits)
        builder.writeUint(d1.toLong(), 8)
        builder.writeUint(d2.toLong(), 8)
        builder.writeBuffer(bitsToPaddedBuffer(cell.bits))
        for (r in refIndices) {
            builder.writeUint(r.toLong(), sizeBytes * 8)
        }
    }

    private fun topologicalSort(root: Cell): List<Pair<Cell, List<Int>>> {
        val pending = mutableListOf(root)
        val allCells = mutableMapOf<String, Pair<Cell, List<String>>>()
        val notPerm = mutableSetOf<String>()
        val sorted = mutableListOf<String>()

        while (pending.isNotEmpty()) {
            val cells = pending.toList()
            pending.clear()
            for (cell in cells) {
                val hash = cell.hash().toHexString()
                if (allCells.containsKey(hash)) continue
                notPerm.add(hash)
                allCells[hash] = cell to cell.refs.map { it.hash().toHexString() }
                for (r in cell.refs) pending.add(r)
            }
        }

        val tempMark = mutableSetOf<String>()
        fun visit(hash: String) {
            if (hash !in notPerm) return
            require(hash !in tempMark) { "Not a DAG" }
            tempMark.add(hash)
            for (r in allCells[hash]!!.second) visit(r)
            sorted.add(0, hash)
            tempMark.remove(hash)
            notPerm.remove(hash)
        }
        while (notPerm.isNotEmpty()) {
            visit(notPerm.first())
        }

        val indexes = sorted.mapIndexed { i, h -> h to i }.toMap()
        return sorted.map { h ->
            val (cell, refHashes) = allCells[h]!!
            cell to refHashes.map { indexes[it]!! }
        }
    }
}

private fun crc32c(data: ByteArray): ByteArray {
    var crc = 0xFFFFFFFFL
    for (b in data) {
        crc = crc xor (b.toLong() and 0xFF)
        repeat(8) {
            crc = if (crc and 1 != 0L) (crc ushr 1) xor 0x82F63B78L else crc ushr 1
        }
    }
    crc = crc xor 0xFFFFFFFFL
    return byteArrayOf(
        crc.toByte(),
        (crc shr 8).toByte(),
        (crc shr 16).toByte(),
        (crc shr 24).toByte()
    )
}

//private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
