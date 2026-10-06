package com.dash.android.aa.media

import com.dash.android.aa.protocol.Aa
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/** Opt-in (-Dsound=1): plays a quiet half-second tone through the real output path and checks pacing. */
class AudioOutProbe {
    @Test fun `real output plays and acknowledges in real time`() {
        if (System.getProperty("sound") == null) return
        val out = AudioOut().apply { volume = 0.05f }
        out.start(Aa.CH_MEDIA_AUDIO)
        val acked = AtomicInteger()
        val chunk = 3840                                   // 20 ms of 48 kHz stereo
        val started = System.currentTimeMillis()
        for (i in 0 until 25) {
            val pcm = ByteArray(chunk)
            for (f in 0 until chunk / 4) {
                val s = (sin((i * chunk / 4 + f) * 2 * Math.PI * 440 / 48000) * 8000).toInt()
                pcm[f * 4] = s.toByte(); pcm[f * 4 + 1] = (s shr 8).toByte(); pcm[f * 4 + 2] = s.toByte(); pcm[f * 4 + 3] = (s shr 8).toByte()
            }
            out.write(Aa.CH_MEDIA_AUDIO, pcm, 0, pcm.size) { acked.incrementAndGet() }
            Thread.sleep(20)
        }
        Thread.sleep(800)
        println("acknowledged ${acked.get()} of 25 in ${System.currentTimeMillis() - started} ms")
        assertEquals(25, acked.get())
        out.stopAll()
    }
}
