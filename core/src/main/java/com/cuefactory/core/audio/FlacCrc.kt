package com.cuefactory.core.audio

/**
 * FLAC CRC helpers (table-driven).
 *
 * Spec polynomials:
 * - header CRC-8:  x^8 + x^2 + x^1 + x^0  (0x07)
 * - frame CRC-16: x^16 + x^15 + x^2 + 1   (0x8005)
 *
 * Batch [crc16Update] is the main hot path for frame indexing. Assembly is
 * only warranted after mmap + batch CRC still show a CPU-bound profile.
 */
internal object FlacCrc {
    private val CRC8_TABLE = IntArray(256) { i ->
        var crc = i
        repeat(8) {
            crc = if ((crc and 0x80) != 0) {
                (crc shl 1) xor 0x07
            } else {
                crc shl 1
            }
        }
        crc and 0xFF
    }

    private val CRC16_TABLE = IntArray(256) { i ->
        var crc = i shl 8
        repeat(8) {
            crc = if ((crc and 0x8000) != 0) {
                (crc shl 1) xor 0x8005
            } else {
                crc shl 1
            }
        }
        crc and 0xFFFF
    }

    fun crc8(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0
        val end = offset + length
        var i = offset
        val table = CRC8_TABLE
        while (i < end) {
            crc = table[crc xor (data[i].toInt() and 0xFF)]
            i++
        }
        return crc
    }

    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        return crc16Update(0, data, offset, length)
    }

    fun crc16Update(crc: Int, value: Int): Int {
        val idx = ((crc shr 8) xor (value and 0xFF)) and 0xFF
        return ((crc shl 8) xor CRC16_TABLE[idx]) and 0xFFFF
    }

    /** Batch CRC-16 over a byte range (hot path for frame body indexing). */
    fun crc16Update(crcIn: Int, data: ByteArray, offset: Int, length: Int): Int {
        var crc = crcIn
        val end = offset + length
        var i = offset
        val table = CRC16_TABLE
        while (i < end) {
            val idx = ((crc shr 8) xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = ((crc shl 8) xor table[idx]) and 0xFFFF
            i++
        }
        return crc
    }

    fun crc16Update(crcIn: Int, data: java.nio.ByteBuffer, offset: Int, length: Int): Int {
        if (length <= 0) return crcIn and 0xFFFF
        // Heap buffers: use array path directly.
        if (data.hasArray()) {
            return crc16Update(crcIn, data.array(), data.arrayOffset() + offset, length)
        }
        // Direct/mmap buffers: bulk-copy into a reusable array. Per-byte
        // ByteBuffer.get() is very slow on Android ART (order-of-magnitude).
        var crc = crcIn
        var off = offset
        var rem = length
        val tmp = directCrcScratch()
        val dup = data.duplicate()
        while (rem > 0) {
            val n = minOf(rem, tmp.size)
            dup.position(off)
            dup.get(tmp, 0, n)
            crc = crc16Update(crc, tmp, 0, n)
            off += n
            rem -= n
        }
        return crc
    }

    fun crc8(data: java.nio.ByteBuffer, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (data.hasArray()) {
            return crc8(data.array(), data.arrayOffset() + offset, length)
        }
        var crc = 0
        val table = CRC8_TABLE
        if (length <= 64) {
            var i = offset
            val end = offset + length
            while (i < end) {
                crc = table[crc xor (data.get(i).toInt() and 0xFF)]
                i++
            }
            return crc
        }
        val tmp = directCrcScratch()
        val dup = data.duplicate()
        var off = offset
        var rem = length
        while (rem > 0) {
            val n = minOf(rem, tmp.size)
            dup.position(off)
            dup.get(tmp, 0, n)
            crc = crc8Update(crc, tmp, 0, n)
            off += n
            rem -= n
        }
        return crc
    }

    private fun crc8Update(crcIn: Int, data: ByteArray, offset: Int, length: Int): Int {
        var crc = crcIn
        val end = offset + length
        var i = offset
        val table = CRC8_TABLE
        while (i < end) {
            crc = table[crc xor (data[i].toInt() and 0xFF)]
            i++
        }
        return crc
    }

    private fun directCrcScratch(): ByteArray {
        var buf = DIRECT_CRC_SCRATCH.get()
        if (buf == null) {
            buf = ByteArray(64 * 1024)
            DIRECT_CRC_SCRATCH.set(buf)
        }
        return buf
    }

    private val DIRECT_CRC_SCRATCH = ThreadLocal<ByteArray>()
}
