package com.dash.android.panel

import androidx.compose.ui.geometry.Offset
import com.dash.android.DashApplication
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SVG transforms must compose as SVG specifies — checked against points worked out by hand, which is
 * also what any browser (and so the panel previewer) draws. Guards the composition-order fix recorded
 * in FORK.md: before it, every case below except the single-step ones landed somewhere else.
 */
class SvgTransformTest {
    private val parser by lazy { SvgParser(SvgSubset.load(DashApplication(createTempDirectory("svgt").toFile()))) }

    private fun nodeMap(svg: String) = parser.parse(svg.byteInputStream()).nodes.associateBy { it.id }

    private fun assertMaps(svg: String, id: String, from: Offset, to: Offset) {
        val node = nodeMap(svg)[id] ?: error("no node $id")
        val got = node.transform.map(from)
        assertTrue(abs(got.x - to.x) < 0.01f && abs(got.y - to.y) < 0.01f, "$id: $from → $got, expected $to")
    }

    private fun svg(body: String) = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">$body</svg>"""

    @Test fun `single translate`() =
        assertMaps(svg("""<rect id="r" x="0" y="0" width="1" height="1" transform="translate(10,20)"/>"""), "r", Offset(1f, 1f), Offset(11f, 21f))

    @Test fun `translate then scale in one attribute scales first`() =
        assertMaps(svg("""<rect id="r" x="0" y="0" width="1" height="1" transform="translate(10,20) scale(2)"/>"""), "r", Offset(1f, 1f), Offset(12f, 22f))

    @Test fun `a scaled element inside a translated group`() =
        assertMaps(svg("""<g transform="translate(10,20)"><rect id="r" x="0" y="0" width="1" height="1" transform="scale(2)"/></g>"""), "r", Offset(1f, 1f), Offset(12f, 22f))

    @Test fun `the Climate glyph pattern - translate and scale on a group`() =
        assertMaps(svg("""<g transform="translate(-309.06,753.75) scale(1.6289)"><path id="p" d="M0 0 h1"/></g>"""), "p", Offset(214f, 48f), Offset(214f * 1.6289f - 309.06f, 48f * 1.6289f + 753.75f))

    @Test fun `rotate about a centre keeps the centre fixed`() =
        assertMaps(svg("""<rect id="r" x="0" y="0" width="1" height="1" transform="rotate(90 50 50)"/>"""), "r", Offset(50f, 50f), Offset(50f, 50f))

    @Test fun `rotate about a centre turns clockwise`() =
        assertMaps(svg("""<rect id="r" x="0" y="0" width="1" height="1" transform="rotate(90 50 50)"/>"""), "r", Offset(60f, 50f), Offset(50f, 60f))
}
