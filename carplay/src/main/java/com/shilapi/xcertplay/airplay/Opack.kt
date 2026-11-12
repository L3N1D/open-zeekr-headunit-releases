// SPDX-License-Identifier: GPL-3.0-or-later
// Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI): livi-cp/src/opack.rs. Additional terms: docs/licenses/LIVI-NOTICE.txt.
package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Apple's OPACK encoding, as CarPlay's vehicle-state data streams carry it.
 *
 * Values map to null, Boolean, Long, Double, String, ByteArray, UUID, List and Map (insertion order kept).
 * Decoding resolves back-references to earlier strings, data, UUIDs and wide numbers.
 */
object Opack {
    private const val TRUE = 0x01
    private const val FALSE = 0x02
    private const val END = 0x03
    private const val NULL = 0x04
    private const val UUID_TAG = 0x05
    private const val DATE = 0x06
    private const val SMALL_INT = 0x08
    private const val SMALL_INT_MAX = 39L
    private const val INT8 = 0x30
    private const val FLOAT32 = 0x35
    private const val FLOAT64 = 0x36
    private const val STRING = 0x40
    private const val DATA = 0x70
    private const val SHORT_LEN = 0x20
    private const val REF = 0xA0
    private const val REF_WIDE = 0xC0
    private const val ARRAY = 0xD0
    private const val DICT = 0xE0
    private const val SHORT_COUNT = 14
    private const val OPEN = 0x0F
    private const val MAX_DEPTH = 64

    fun decode(bytes: ByteArray): Any? {
        val reader = Reader(bytes)
        val value = reader.value()
        require(reader.at == bytes.size) { "opack: trailing bytes" }
        return value
    }

    fun encode(value: Any?): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private class Reader(val buf: ByteArray) {
        var at = 0
        private val seen = ArrayList<Any?>()
        private var depth = 0

        fun take(n: Int): ByteArray {
            require(n >= 0 && at + n <= buf.size) { "opack: value runs past the data" }
            return buf.copyOfRange(at, at + n).also { at += n }
        }

        fun unsigned(width: Int): Long {
            val bytes = take(width)
            var value = 0L
            for (index in bytes.indices.reversed()) value = (value shl 8) or (bytes[index].toLong() and 0xff)
            return value
        }

        fun signed(width: Int): Long {
            val shift = 64 - 8 * width
            return (unsigned(width) shl shift) shr shift
        }

        fun length(tag: Int, base: Int): Int {
            val n = tag - base
            if (n <= SHORT_LEN) return n
            val length = unsigned(n - SHORT_LEN)
            require(length in 0..buf.size.toLong()) { "opack: length larger than the data" }
            return length.toInt()
        }

        fun remember(value: Any?): Any? = value.also { seen += it }

        fun value(): Any? {
            require(++depth <= MAX_DEPTH) { "opack: nested too deep" }
            return item().also { depth-- }
        }

        private fun item(): Any? {
            val tag = take(1)[0].toInt() and 0xff
            return when (tag) {
                TRUE -> true
                FALSE -> false
                NULL -> null
                UUID_TAG -> {
                    val b = take(16)
                    remember(UUID(java.nio.ByteBuffer.wrap(b, 0, 8).long, java.nio.ByteBuffer.wrap(b, 8, 8).long))
                }
                DATE, FLOAT64 -> remember(Double.fromBits(unsigned(8)))
                FLOAT32 -> remember(Float.fromBits(unsigned(4).toInt()).toDouble())
                in SMALL_INT until INT8 -> (tag - SMALL_INT).toLong()
                in 0x30..0x33 -> remember(signed(1 shl (tag - INT8)))
                in 0x40..0x64 -> remember(String(take(length(tag, STRING)), Charsets.UTF_8))
                in 0x70..0x94 -> remember(take(length(tag, DATA)))
                in REF..REF_WIDE -> seen.getOrNull(tag - REF) ?: throw IllegalArgumentException("opack: bad reference")
                in 0xC1..0xC4 -> seen.getOrNull(unsigned(tag - REF_WIDE).toInt()) ?: throw IllegalArgumentException("opack: bad reference")
                in 0xD0..0xDF -> {
                    val count = (tag - ARRAY).takeIf { it != OPEN }
                    val items = ArrayList<Any?>()
                    while (count == null || items.size < count) {
                        if (count == null && buf.getOrNull(at)?.toInt() == END) { at++; break }
                        items += value()
                    }
                    items
                }
                in 0xE0..0xEF -> {
                    val count = (tag - DICT).takeIf { it != OPEN }
                    val entries = LinkedHashMap<Any?, Any?>()
                    var read = 0
                    while (count == null || read < count) {
                        if (count == null && buf.getOrNull(at)?.toInt() == END) { at++; break }
                        val key = value()
                        entries[key] = value()
                        read++
                    }
                    entries
                }
                else -> throw IllegalArgumentException("opack: unsupported tag 0x${tag.toString(16)}")
            }
        }
    }

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(NULL)
            is Boolean -> out.write(if (value) TRUE else FALSE)
            is Byte, is Short, is Int, is Long -> writeInt(out, (value as Number).toLong())
            is Float, is Double -> {
                out.write(FLOAT64)
                out.write(littleEndian((value as Number).toDouble().toRawBits(), 8))
            }
            is String -> value.toByteArray(Charsets.UTF_8).let { writeLength(out, STRING, it.size); out.write(it) }
            is ByteArray -> { writeLength(out, DATA, value.size); out.write(value) }
            is UUID -> {
                out.write(UUID_TAG)
                out.write(java.nio.ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array())
            }
            is List<*> -> {
                val open = writeCount(out, ARRAY, value.size)
                value.forEach { write(out, it) }
                if (open) out.write(END)
            }
            is Map<*, *> -> {
                val open = writeCount(out, DICT, value.size)
                value.forEach { (k, v) -> write(out, k); write(out, v) }
                if (open) out.write(END)
            }
            else -> throw IllegalArgumentException("opack: unsupported value ${value.javaClass.simpleName}")
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, n: Long) {
        if (n in 0..SMALL_INT_MAX) { out.write(SMALL_INT + n.toInt()); return }
        val width = when (n) {
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> 1
            in Short.MIN_VALUE..Short.MAX_VALUE -> 2
            in Int.MIN_VALUE..Int.MAX_VALUE -> 4
            else -> 8
        }
        out.write(INT8 + Integer.numberOfTrailingZeros(width))
        out.write(littleEndian(n, width))
    }

    private fun writeLength(out: ByteArrayOutputStream, base: Int, n: Int) {
        if (n <= SHORT_LEN) { out.write(base + n); return }
        val width = when {
            n <= 0xFF -> 1
            n <= 0xFFFF -> 2
            n <= 0xFFFFFF -> 3
            else -> 4
        }
        out.write(base + SHORT_LEN + width)
        out.write(littleEndian(n.toLong(), width))
    }

    private fun writeCount(out: ByteArrayOutputStream, base: Int, n: Int): Boolean =
        if (n <= SHORT_COUNT) { out.write(base + n); false } else { out.write(base + OPEN); true }

    private fun littleEndian(value: Long, width: Int) = ByteArray(width) { ((value ushr (8 * it)) and 0xff).toByte() }
}
