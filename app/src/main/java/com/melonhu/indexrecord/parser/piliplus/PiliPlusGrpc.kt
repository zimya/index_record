package com.melonhu.indexrecord.parser.piliplus

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.GZIPInputStream

private const val FRAME_HEADER_BYTES = 5
private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
private const val MAX_PROTO_BYTES = 16 * 1024 * 1024

/** GrpcReq uses a compression flag followed by a four-byte big-endian length. */
internal fun decodeGrpcFrame(encoded: String): ByteArray {
    require(encoded.length <= ((FRAME_HEADER_BYTES + MAX_FRAME_BYTES + 2) / 3) * 4) { "gRPC frame exceeds size limit" }
    val frame = Base64.getDecoder().decode(encoded)
    require(frame.size in FRAME_HEADER_BYTES..(FRAME_HEADER_BYTES + MAX_FRAME_BYTES)) { "Invalid gRPC frame size" }
    val length = ByteBuffer.wrap(frame, 1, 4).int
    require(length == frame.size - FRAME_HEADER_BYTES) { "Expected exactly one gRPC message frame" }
    return when (frame[0].toInt()) {
        0 -> frame.copyOfRange(FRAME_HEADER_BYTES, frame.size)
        1 -> GZIPInputStream(ByteArrayInputStream(frame, FRAME_HEADER_BYTES, length)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(count <= MAX_PROTO_BYTES - output.size()) { "Inflated gRPC message exceeds size limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        else -> throw IllegalArgumentException("Unsupported gRPC compression flag")
    }
}

/** Bounded slices read only the protobuf wire types used by this schema. */
internal class ProtoReader(
    private val bytes: ByteArray,
    private var position: Int = 0,
    private val end: Int = bytes.size
) {
    fun fields(visit: ProtoReader.(number: Int, wire: Int) -> Unit) {
        while (position < end) {
            val tag = varint()
            require(tag in 8..0xffff_ffffL) { "Invalid protobuf tag" }
            visit((tag ushr 3).toInt(), (tag and 7).toInt())
        }
    }

    fun int64(wire: Int): Long {
        require(wire == 0) { "Expected protobuf varint" }
        return varint()
    }

    fun message(wire: Int): ProtoReader {
        val start = readBytes(wire)
        return ProtoReader(bytes, start, position)
    }

    fun string(wire: Int): String {
        val start = readBytes(wire)
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, start, position - start)).toString()
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> advance(8)
            2 -> readBytes(wire)
            5 -> advance(4)
            else -> throw IllegalArgumentException("Unsupported protobuf wire type")
        }
    }

    private fun readBytes(wire: Int): Int {
        require(wire == 2) { "Expected protobuf length-delimited field" }
        return advance(varint())
    }

    private fun advance(count: Long): Int {
        require(count in 0..(end - position).toLong()) { "Truncated or invalid protobuf field length" }
        val start = position
        position += count.toInt()
        return start
    }

    private fun varint(): Long {
        var value = 0L
        for (shift in 0..63 step 7) {
            require(position < end) { "Truncated protobuf varint" }
            val byte = bytes[position++].toInt() and 0xff
            require(shift < 63 || byte <= 1) { "Overflowing protobuf varint" }
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte < 0x80) return value
        }
        error("Invalid protobuf varint")
    }
}
