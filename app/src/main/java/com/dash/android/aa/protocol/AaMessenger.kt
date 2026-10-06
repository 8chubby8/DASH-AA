package com.dash.android.aa.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** A byte pipe to the phone — USB bulk endpoints in the car, a socket pair in the tests. */
interface AaLink : AutoCloseable {
    val description: String
    /** Blocking read of whatever has arrived; -1 at end of stream. */
    fun read(buffer: ByteArray): Int
    fun write(data: ByteArray)
    override fun close()
}

/** One whole Android Auto message, reassembled from its frames. */
class AaMessage(
    val channel: Int,
    val encrypted: Boolean,
    val control: Boolean,
    val id: Int,
    val payload: ByteArray,
) {
    override fun toString() =
        "${Aa.channelName(channel)} 0x%04x%s %d bytes".format(id, if (control) " (ctl)" else "", payload.size)
}

/**
 * Android Auto's framing, both directions.
 *
 * A frame is `channel(1) flags(1) size(2)` — plus `total(4)` on the first frame of a split message —
 * then the payload, which is TLS ciphertext once the handshake is done. A message longer than 16 KB of
 * plaintext is split FIRST / MIDDLE… / LAST, each piece encrypted on its own; the reader decrypts each
 * frame and joins the plaintext.
 *
 * **Reassembly is per channel.** aasdk assumes a split message's frames arrive back to back and rejects
 * anything else; phones are free to interleave a sensor or audio frame between two halves of a video
 * frame, so DASH-AA keeps one partial message per channel and never assumes adjacency.
 */
class AaMessenger(private val link: AaLink, private val tls: AaTls) {

    private val input = LinkInputStream(link)
    private val partial = HashMap<Int, ByteArrayOutputStream>()
    private val writeLock = Any()

    /** Blocking: the next complete message from the phone. Throws [EOFException] when the link ends. */
    fun receive(): AaMessage {
        while (true) {
            val channel = input.readByte()
            val flags = input.readByte()
            val frameType = flags and Aa.FRAME_BULK
            val size = (input.readByte() shl 8) or input.readByte()
            if (frameType == Aa.FRAME_FIRST) input.skipFully(4)       // total size — not needed
            val raw = input.readFully(size)
            val encrypted = flags and Aa.FLAG_ENCRYPTED != 0
            val plain = if (encrypted) tls.decrypt(raw) else raw

            val message: ByteArray = when (frameType) {
                Aa.FRAME_BULK -> plain
                Aa.FRAME_FIRST -> { partial[channel] = ByteArrayOutputStream().apply { write(plain) }; continue }
                Aa.FRAME_LAST -> {
                    val buf = partial.remove(channel) ?: continue           // a LAST without its FIRST
                    buf.write(plain); buf.toByteArray()
                }
                else -> { partial[channel]?.write(plain); continue }        // MIDDLE
            }
            if (message.size < 2) continue
            val id = ((message[0].toInt() and 0xFF) shl 8) or (message[1].toInt() and 0xFF)
            return AaMessage(channel, encrypted, flags and Aa.FLAG_CONTROL != 0, id, message.copyOfRange(2, message.size))
        }
    }

    /** Send one message; split, encrypted and written as one uninterrupted run of frames. */
    fun send(channel: Int, id: Int, payload: ByteArray, encrypted: Boolean = true, control: Boolean = false) {
        val plain = ByteArray(payload.size + 2)
        plain[0] = (id shr 8).toByte()
        plain[1] = id.toByte()
        payload.copyInto(plain, 2)

        synchronized(writeLock) {
            val out = ByteArrayOutputStream(plain.size + 64)
            var offset = 0
            do {
                val chunk = minOf(Aa.MAX_FRAME_PAYLOAD, plain.size - offset)
                val first = offset == 0
                val last = offset + chunk >= plain.size
                val frameType = when {
                    first && last -> Aa.FRAME_BULK
                    first -> Aa.FRAME_FIRST
                    last -> Aa.FRAME_LAST
                    else -> 0
                }
                val piece = plain.copyOfRange(offset, offset + chunk)
                val body = if (encrypted) tls.encrypt(piece) else piece
                var flags = frameType
                if (encrypted) flags = flags or Aa.FLAG_ENCRYPTED
                if (control) flags = flags or Aa.FLAG_CONTROL
                out.write(channel)
                out.write(flags)
                out.write(body.size shr 8)
                out.write(body.size and 0xFF)
                if (frameType == Aa.FRAME_FIRST) {
                    out.write(plain.size ushr 24); out.write(plain.size ushr 16)
                    out.write(plain.size ushr 8); out.write(plain.size)
                }
                out.write(body)
                offset += chunk
            } while (offset < plain.size)
            link.write(out.toByteArray())
        }
    }

    /** Buffers the link's arbitrarily-sized reads into exact byte counts. */
    private class LinkInputStream(private val link: AaLink) : InputStream() {
        private val buf = ByteArray(65536)
        private var pos = 0
        private var end = 0

        private fun fill() {
            while (pos >= end) {
                val n = link.read(buf)            // 0 = a timed-out USB read; try again
                if (n < 0) throw EOFException("Android Auto link closed")
                pos = 0; end = n
            }
        }

        override fun read(): Int { fill(); return buf[pos++].toInt() and 0xFF }
        fun readByte(): Int = read()

        fun readFully(n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                fill()
                val take = minOf(n - got, end - pos)
                System.arraycopy(buf, pos, out, got, take)
                pos += take; got += take
            }
            return out
        }

        fun skipFully(n: Int) { readFully(n) }
    }
}
