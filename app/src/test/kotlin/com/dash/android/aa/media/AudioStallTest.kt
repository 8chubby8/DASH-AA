package com.dash.android.aa.media

import com.dash.android.aa.protocol.Aa
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A sound system that stops taking audio must never stop the phone (seen 2026-10-05: WirePlumber
 * wedged, the phone waited for acknowledgements that never came, and dropped Android Auto).
 */
class AudioStallTest {
    @Test fun `a frozen sound system still gets every chunk acknowledged`() {
        val forever = CountDownLatch(1)
        val frozen = object : PcmOut {
            override fun write(pcm: ByteArray) { forever.await() }       // never returns
            override fun close() { forever.countDown() }
        }
        var stuck = false
        val out = AudioOut { _, _, _, _ -> frozen }.apply { onStuck = { stuck = true } }
        out.start(Aa.CH_MEDIA_AUDIO)
        val acked = AtomicInteger()
        val first = System.currentTimeMillis()
        // As the phone does: send one, wait for its acknowledgement, send the next.
        repeat(5) {
            val done = CountDownLatch(1)
            out.write(Aa.CH_MEDIA_AUDIO, ByteArray(3840), 0, 3840) { acked.incrementAndGet(); done.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS), "chunk ${it + 1} was never acknowledged")
        }
        assertEquals(5, acked.get())
        assertTrue(stuck, "the stall is reported")
        assertTrue(System.currentTimeMillis() - first < 2000, "the phone is unblocked quickly")
        out.stopAll()
    }
}
