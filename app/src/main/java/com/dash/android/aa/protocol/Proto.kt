package com.dash.android.aa.protocol

import java.io.ByteArrayOutputStream

/**
 * A minimal protocol-buffers codec — just what the Android Auto messages need.
 *
 * **Why hand-rolled rather than generated.** Android Auto's messages are small, few, and fixed: a
 * couple of dozen, mostly two or three fields. A codegen toolchain (protoc, a Gradle plugin, a
 * runtime) to produce them would be the largest moving part in the stack for the least work. The field
 * numbers are taken from aasdk's published `.proto` files (GPL-3.0, like DASH), and every message is
 * built in one place — [AaMessages] — so a field number is written exactly once.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()

    fun uint(field: Int, value: Long): ProtoWriter { tag(field, 0); varint(value); return this }
    fun uint(field: Int, value: Int): ProtoWriter = uint(field, value.toLong() and 0xFFFFFFFFL)
    /** int32 is sign-extended to 64 bits on the wire, exactly as protobuf specifies. */
    fun int(field: Int, value: Int): ProtoWriter { tag(field, 0); varint(value.toLong()); return this }
    fun bool(field: Int, value: Boolean): ProtoWriter = uint(field, if (value) 1L else 0L)
    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun bytes(field: Int, value: ByteArray): ProtoWriter { tag(field, 2); varint(value.size.toLong()); out.write(value); return this }
    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter = bytes(field, ProtoWriter().apply(build).bytes())

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    private fun varint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) { out.write(v.toInt()); return }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}

/** Builds one encoded message. */
fun proto(build: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(build).bytes()

/**
 * A decoded message: every field by number, in arrival order. Repeated fields keep every occurrence;
 * unknown fields are kept and simply never asked for — protobuf's own forward-compatibility, which is
 * also DASH's browser rule.
 */
class ProtoMessage private constructor(private val fields: List<Pair<Int, Any>>) {

    fun long(field: Int): Long? = fields.lastOrNull { it.first == field }?.second as? Long
    fun int(field: Int): Int? = long(field)?.toInt()
    fun bool(field: Int): Boolean? = long(field)?.let { it != 0L }
    fun bytes(field: Int): ByteArray? = fields.lastOrNull { it.first == field }?.second as? ByteArray
    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }
    fun repeatedBytes(field: Int): List<ByteArray> = fields.filter { it.first == field }.mapNotNull { it.second as? ByteArray }
    fun repeatedMessages(field: Int): List<ProtoMessage> = repeatedBytes(field).map { parse(it) }
    fun repeatedLong(field: Int): List<Long> = fields.filter { it.first == field }.flatMap { (_, v) ->
        when (v) {
            is Long -> listOf(v)
            is ByteArray -> unpackVarints(v)          // packed repeated scalars
            else -> emptyList()
        }
    }

    override fun toString(): String = fields.joinToString(", ", "{", "}") { (f, v) ->
        "$f=" + when (v) { is ByteArray -> "[${v.size}b]"; else -> v.toString() }
    }

    companion object {
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ProtoMessage {
            val r = Reader(data, offset, offset + length)
            val fields = mutableListOf<Pair<Int, Any>>()
            while (r.hasMore()) {
                val key = r.varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> fields += field to r.varint()
                    1 -> { r.skip(8) }
                    2 -> fields += field to r.take(r.varint().toInt())
                    5 -> { r.skip(4) }
                    else -> break                              // malformed — keep what was read
                }
            }
            return ProtoMessage(fields)
        }

        private fun unpackVarints(b: ByteArray): List<Long> {
            val r = Reader(b, 0, b.size)
            val out = mutableListOf<Long>()
            while (r.hasMore()) out += r.varint()
            return out
        }
    }

    private class Reader(private val d: ByteArray, private var p: Int, private val end: Int) {
        fun hasMore() = p < end
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (p < end) {
                val b = d[p++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return result
        }
        fun take(n: Int): ByteArray {
            val stop = minOf(end, p + n)
            return d.copyOfRange(p, stop).also { p = stop }
        }
        fun skip(n: Int) { p = minOf(end, p + n) }
    }
}
