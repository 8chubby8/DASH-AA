package com.dash.android.transport

import java.io.ByteArrayOutputStream

/**
 * Reassembles an inbound byte stream into framed [Inbound] units. It mirrors the firmware's own
 * framing exactly (arduino/arduino.md §1, §8) and understands the wire's **two** framing rules:
 *
 *  - **Newline framing (the default).** A line ends at '\n'; a stray '\r' is tolerated and dropped
 *    (so CRLF works); an over-long line is discarded rather than allowed to grow without bound. Bytes
 *    are decoded as UTF-8 only once a full line has arrived, so a multi-byte character split across
 *    two reads is handled correctly. Every message is a line — except one.
 *
 *  - **Length-prefix framing (asset blocks).** A completed line of the shape `BLOCK|id|name|length|crc`
 *    is *not* a message in its own right: it announces that exactly `length` raw bytes follow, and
 *    those bytes may contain '\n'. On seeing such a header the assembler switches to raw mode, reads
 *    the byte count verbatim, and emits the header and bytes together as one [Inbound.Block].
 *
 * The switch happens **synchronously, inside the byte loop, on the single reader thread** — this is
 * the whole point. If the decision to read raw bytes were made asynchronously anywhere above this
 * class, the reader would already have chewed the payload into mis-framed lines before the decision
 * landed. Because framing is decided here, in step with byte consumption, there is no such race.
 *
 * This class does **framing only** — where does each unit begin and end. It never validates a block's
 * CRC, never assembles a record, never interprets a message. That is the job of the layers above
 * (the install desk, 1.4.4). Not thread-safe: feed it from one reader thread (the usb-serial IO
 * thread); call [reset] only when that thread is idle (i.e. between connections).
 */
class FrameAssembler(
    private val maxLineBytes: Int = 1024,
    /**
     * Upper bound on a single asset block — **a sanity guard, not an asset-size policy** (roadmap
     * 1.6.5). It exists so a corrupt or hostile header claiming two gigabytes cannot make DASH try
     * to allocate two gigabytes. It is deliberately far above anything a real module sends.
     *
     * **It used to be 64 KB**, carrying the note *"the real asset-size caps are an open item
     * (arduino.md §10); this is a safe default"*. That open item has since been closed the other
     * way — `module-layout.md` §2 rules that **there are no asset caps**; DASH advises on size and
     * degrades gracefully rather than refusing. The first real ACCESSORY payload built against the
     * specification was 78 KB and would have been rejected by a limit the specification no longer
     * endorses, so the number is now set where a guard belongs rather than where a policy would.
     *
     * *Known and deliberate: a block is still buffered whole before its CRC can be checked, so this
     * bound is also a heap bound. Streaming a large block to disk as it arrives is the eventual
     * answer and is not needed at the sizes real panels use.*
     */
    private val maxBlockBytes: Int = 8 * 1024 * 1024,
    private val onInbound: (Inbound) -> Unit
) {
    private val buffer = ByteArrayOutputStream()
    private var overflowed = false

    // Raw-mode state: while rawRemaining > 0 we are reading a block payload, not a line.
    private val rawBuffer = ByteArrayOutputStream()
    private var rawRemaining = 0
    private var pendingHeader: String? = null

    /** In raw mode, but counting the bytes past rather than keeping them — see [completeLine]. */
    private var discarding = false

    /** Payload size at the last [Inbound.BlockProgress] — the throttle's watermark. */
    private var lastProgressAt = 0

    fun feed(data: ByteArray, length: Int) {
        var i = 0
        while (i < length) {
            if (rawRemaining > 0) {
                // Raw mode: take as many payload bytes as this chunk offers, in one copy. A '\n' in
                // here is payload, counted like any other byte — never a delimiter.
                val take = minOf(rawRemaining, length - i)
                if (!discarding) rawBuffer.write(data, i, take)   // discarding: counted, not kept
                i += take
                rawRemaining -= take
                if (rawRemaining == 0) emitBlock() else emitProgress()
            } else {
                when (val b = data[i++].toInt()) {
                    NEWLINE -> completeLine()
                    CARRIAGE_RETURN -> { /* tolerate CRLF: drop the CR */ }
                    else -> {
                        if (buffer.size() < maxLineBytes) buffer.write(b)
                        else overflowed = true   // over-long line: drop the whole thing safely
                    }
                }
            }
        }
    }

    /** A line just ended. Either it announces a block (switch to raw mode) or it is an ordinary line. */
    private fun completeLine() {
        val wasOverflow = overflowed
        val line = buffer.toString(Charsets.UTF_8.name())
        buffer.reset()
        overflowed = false
        if (wasOverflow) return   // over-long line already discarded

        val blockLen = blockLengthOrNull(line)
        when {
            blockLen == null -> onInbound(Inbound.Line(line))          // ordinary message
            blockLen == 0 -> onInbound(Inbound.Block(line, ByteArray(0)))
            blockLen > maxBlockBytes -> {
                // Too large to hold — but the bytes are still coming, and they are still bytes. Read
                // and discard them so the framing stays in step; anything else shreds a payload full
                // of newlines into nonsense lines and corrupts every message after it. This used to
                // pass the header on as an ordinary line and do exactly that.
                pendingHeader = line
                rawRemaining = blockLen
                discarding = true
            }
            else -> {                                                  // switch to raw mode
                pendingHeader = line
                rawRemaining = blockLen
            }
        }
    }

    /**
     * Say how far through the current block we are, at most once per [PROGRESS_STEP_BYTES].
     *
     * **Throttled because a UART hands over tiny reads.** At 115200 baud the driver delivers a few
     * dozen bytes at a time, so an unthrottled report would fire thousands of times per asset and
     * spend more effort saying the install is happening than on the install. Every 4 KB gives around
     * twenty updates across a typical panel payload, which is smooth to the eye and costs nothing.
     *
     * **A discarded over-size block reports nothing.** Its bytes are being counted past, not
     * received, and there is no install to move a bar for — the desk is about to be told the block
     * was refused, and progress towards something already lost would be a lie.
     */
    private fun emitProgress() {
        if (discarding) return
        val header = pendingHeader ?: return
        val received = rawBuffer.size()
        if (received - lastProgressAt < PROGRESS_STEP_BYTES) return
        lastProgressAt = received
        onInbound(Inbound.BlockProgress(header, received, received + rawRemaining))
    }

    private fun emitBlock() {
        val header = pendingHeader ?: return
        if (discarding) {
            onInbound(Inbound.OversizeBlock(header, blockLengthOrNull(header) ?: 0))
            discarding = false
        } else {
            onInbound(Inbound.Block(header, rawBuffer.toByteArray()))
        }
        rawBuffer.reset()
        pendingHeader = null
        lastProgressAt = 0
    }

    /**
     * The declared byte length if [line] is a well-formed block header, else null. The header is
     * exactly five fields `BLOCK|id|name|length|crc` (delimiters can't appear inside a field, §2), so
     * anything else is treated as an ordinary line, not a header.
     */
    private fun blockLengthOrNull(line: String): Int? {
        if (!line.startsWith("BLOCK|")) return null
        val parts = line.split('|')
        if (parts.size != 5) return null
        val len = parts[3].trim().toIntOrNull() ?: return null
        return if (len < 0) null else len
    }

    /** Clear all framing state. Call between connections so a mid-block disconnect can't corrupt the
     *  next session's framing. Only safe to call while the reader thread is idle. */
    fun reset() {
        buffer.reset()
        overflowed = false
        rawBuffer.reset()
        rawRemaining = 0
        pendingHeader = null
        discarding = false
        lastProgressAt = 0
    }

    private companion object {
        const val NEWLINE = '\n'.code
        const val CARRIAGE_RETURN = '\r'.code

        /** How much payload must arrive between progress reports. See [emitProgress]. */
        const val PROGRESS_STEP_BYTES = 4 * 1024
    }
}
