package com.melonhu.indexrecord.parser.tieba

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded, zero-copy protobuf reader. Unknown length-delimited fields are never decoded. */
internal class TiebaWireReader private constructor(
    private val bytes: ByteArray,
    private var position: Int,
    private val limit: Int,
    private val depth: Int,
    private val budget: Budget
) {
    internal class Malformed(message: String) : IllegalArgumentException(message)
    private class Budget(var fields: Int = 0)

    constructor(bytes: ByteArray) : this(bytes, 0, bytes.size, 0, Budget()) {
        if (bytes.size > MAX_BYTES) fail("Message too large")
    }

    val exhausted: Boolean get() = position == limit

    fun tag(): Int {
        if (++budget.fields > MAX_FIELDS) fail("Too many fields")
        val value = varint()
        if (value <= 0 || value > 0xffffffffL || value ushr 3 == 0L) fail("Invalid tag")
        val wire = (value and 7).toInt()
        if (wire > 5) fail("Invalid wire type")
        return value.toInt()
    }

    fun varint(): Long {
        var result = 0L
        for (index in 0..9) {
            if (position >= limit) fail("Truncated varint")
            val value = bytes[position++].toInt() and 0xff
            if (index == 9 && value > 1) fail("Varint overflow")
            result = result or ((value and 0x7f).toLong() shl (index * 7))
            if (value and 0x80 == 0) return result
        }
        fail("Unterminated varint")
    }

    private fun length(): Int {
        val size = varint()
        if (size < 0 || size > (limit - position).toLong()) fail("Invalid length")
        return size.toInt()
    }

    fun message(): TiebaWireReader {
        val size = length()
        if (depth >= MAX_DEPTH) fail("Message nesting too deep")
        val child = TiebaWireReader(bytes, position, position + size, depth + 1, budget)
        position += size
        return child
    }

    fun string(): String {
        val size = length()
        val value = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, position, size)).toString()
        position += size
        return value
    }

    fun skip(tag: Int) = skip(tag, depth)

    private fun skip(tag: Int, nesting: Int) {
        when (tag and 7) {
            0 -> varint()
            1 -> advance(8)
            2 -> advance(length())
            3 -> {
                if (nesting >= MAX_DEPTH) fail("Group nesting too deep")
                while (true) {
                    if (exhausted) fail("Unterminated group")
                    val next = tag()
                    if (next and 7 == 4) {
                        if (next ushr 3 != tag ushr 3) fail("Mismatched group")
                        break
                    }
                    skip(next, nesting + 1)
                }
            }
            4 -> fail("Unexpected end group")
            5 -> advance(4)
            else -> fail("Invalid wire type")
        }
    }

    private fun advance(size: Int) {
        if (size < 0 || size > limit - position) fail("Truncated field")
        position += size
    }

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        private const val MAX_FIELDS = 200_000
        private const val MAX_DEPTH = 32
        private fun fail(message: String): Nothing = throw Malformed(message)
    }
}
