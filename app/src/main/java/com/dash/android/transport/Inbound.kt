package com.dash.android.transport

/**
 * One framed unit arriving from a module (module → DASH), as produced by the [FrameAssembler].
 *
 * The wire carries two kinds of framing (arduino/arduino.md §1, §8):
 *
 *  - **[Line]** — the ordinary case: a message is one line of UTF-8 text ended by '\n'. Every message
 *    in the grammar is a line *except* the raw bytes of an asset block.
 *  - **[Block]** — an ACCESSORY asset payload: a `BLOCK|id|name|length|crc` header followed by exactly
 *    `length` raw bytes, which may themselves contain '\n' (that is *why* the block is length-prefixed
 *    rather than newline-terminated). The assembler reads the byte count verbatim and hands the whole
 *    thing up as one unit, header and bytes together.
 *
 * Both variants flow through a **single ordered stream**, so a `MANIFEST` line, the `BLOCK`s that
 * follow it, and the closing `INSTALL_END` line reach the brain in exactly the order the module sent
 * them — which the install handshake (roadmap 1.4.4) relies on for progress accounting.
 *
 * Framing lives in the transport (where the bytes are); *meaning* — CRC validation, record assembly —
 * lives above it in the install desk. The assembler never validates a block; it only carves it out.
 */
sealed interface Inbound {
    data class Line(val text: String) : Inbound

    class Block(val header: String, val bytes: ByteArray) : Inbound {
        // A rendering of the payload for the wire tap / Serial Monitor — never the raw bytes as text
        // (that would spew binary), just a readable note that this many bytes arrived.
        val note: String get() = "«${bytes.size} bytes»"
    }

    /**
     * A block whose header declared more bytes than DASH is willing to hold in memory at once
     * (roadmap 1.6.5). The payload was **read and discarded** rather than kept — so the stream stays
     * correctly framed and the next message parses normally — and this says so honestly.
     *
     * **Why it is not simply dropped.** The assembler must consume the declared bytes either way, or
     * a payload full of newlines would be shredded into nonsense lines. Having consumed them, saying
     * nothing would leave the install to die of the idle timeout with "stalled" against its name,
     * which is not what happened. This carries the real reason up to the desk that can report it.
     */
    class OversizeBlock(val header: String, val declaredBytes: Int) : Inbound {
        val note: String get() = "«$declaredBytes bytes — too large, discarded»"
    }

    /**
     * How far through the block currently being read the assembler has got (roadmap 1.6.6).
     *
     * **The one [Inbound] that is not a completed unit**, and it belongs here rather than in a side
     * channel because it is a *framing* fact — how far into the current frame we are — which is
     * exactly what this class knows and nothing above it does. Riding the normal stream also means
     * it arrives stamped with its device origin for free, in order with the block it describes.
     *
     * **Why it had to exist.** The install progress bar advanced only when a whole block committed,
     * so a three-block ACCESSORY payload gave it three sample points — and since the artwork is
     * typically ~90% of the payload, the bar sat at 1%, jumped to 90%, and finished. Over USB, where
     * 88 KB takes some eight seconds, that reads as a hung install. The assembler was counting the
     * bytes down the whole time and saying nothing.
     *
     * **Advisory only.** [received] is bytes *arrived*, not bytes *accepted* — nothing here has been
     * CRC-checked and the block may yet fail. The install desk must use it for display and never let
     * it reach a committed total, or a failed block would leave phantom progress behind.
     */
    class BlockProgress(val header: String, val received: Int, val declared: Int) : Inbound
}

/**
 * A framed [Inbound] unit plus its **origin** — which transport, and which device on that transport,
 * delivered it (roadmap 1.4.14). The origin is stamped where the frame is assembled (inside the
 * per-device/per-socket connection, which already knows its key) and travels up with the frame, so
 * the layers above can do two things they couldn't before:
 *
 *  - **Attribute a module to its pipe** — the wired-vs-wireless distinction the reconciliation desk
 *    uses to word a "not responding" module correctly (a dead cable reads differently from a
 *    wireless module out of range).
 *  - **Fail the right install on a disconnect** — when the device carrying an in-flight install
 *    handshake leaves the bus, the install desk can abort *that* session at once, instead of waiting
 *    out the idle timeout.
 *
 * [deviceKey] pairs with [transportTag] to identify a [TransportDevice]. A transport that never
 * distinguishes devices may leave it null and rely on the tag alone.
 */
data class InboundFrame(
    val frame: Inbound,
    val transportTag: String,
    val deviceKey: String?
)

/**
 * A specific device on a specific transport (roadmap 1.4.14) — the pair that identifies where an
 * install's declarations are arriving from, and, matched against the live device list, which device
 * leaving the bus should fail an in-flight install. Distinct from [TransportDevice]: this is the bare
 * identity used for matching, not the richer object the Serial Monitor renders.
 */
data class DeviceRef(val transportTag: String, val deviceKey: String)
