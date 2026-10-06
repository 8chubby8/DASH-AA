package com.dash.android.panel

import android.util.Xml
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathParser
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * SVG → [VectorArt], against the subset in [SvgSubset] (roadmap 1.6.6).
 *
 * **The browser rule** (`module-layout.md` §9): DASH draws what it understands and skips what it
 * does not. Nothing in here throws. An author who used one thing outside the subset gets a panel
 * missing one flourish and a warning naming it — never a dead panel, and never an error.
 *
 * Path data is handed to Compose's own [PathParser] — the same parser Android uses for
 * VectorDrawables — so the one genuinely intricate part of SVG is inherited rather than
 * reimplemented, arcs and all.
 */
class SvgParser(private val subset: SvgSubset) {

    private val warnings = mutableListOf<Warning>()
    private val nodes = mutableListOf<ArtNode>()
    private val gradients = mutableMapOf<String, GradientSpec>()

    /** Paint references resolve after the parse: SVG permits a gradient defined after its use. */
    private val pending = mutableListOf<PendingNode>()

    private class PendingNode(
        val id: String?,
        val path: Path,
        val style: Map<String, String>,
        val opacity: Float,
        val transform: Matrix,
    )

    fun parse(source: InputStream): VectorArt {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(source.reader())
        }

        var viewBox = Rect(Offset.Zero, Size(100f, 100f))
        val styleStack = ArrayDeque<Map<String, String>>().apply { addLast(emptyMap()) }
        val transformStack = ArrayDeque<Matrix>().apply { addLast(Matrix()) }
        val opacityStack = ArrayDeque<Float>().apply { addLast(1f) }
        var depth = 0
        var defsDepth = -1

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    val name = parser.name
                    val namespace = parser.namespace

                    // Tool furniture: sodipodi:namedview, inkscape:*, the RDF metadata block.
                    if (subset.isIgnoredNamespace(namespace) || subset.isIgnoredElement(name)) {
                        skipSubtree(parser); depth--
                        continue
                    }

                    val attrs = attributesOf(parser, name)

                    subset.exclusionForElement(name)?.let { excluded ->
                        warn(Severity.SKIPPED, describe(name, attrs["id"]),
                            "${excluded.reason} ${excluded.advice}")
                        skipSubtree(parser); depth--
                        continue
                    }

                    if (!subset.allowsElement(name)) {
                        warn(Severity.SKIPPED, describe(name, attrs["id"]),
                            "Not part of the SVG subset DASH renders. It is not drawn.")
                        skipSubtree(parser); depth--
                        continue
                    }

                    when (name) {
                        "svg" -> viewBox = readViewBox(attrs)

                        "linearGradient", "radialGradient" -> {
                            readGradient(parser, name, attrs)
                            depth--
                            continue
                        }

                        "defs" -> if (defsDepth < 0) defsDepth = depth

                        "g" -> {
                            // A group contributes inherited style and a transform, nothing drawable.
                            styleStack.addLast(inheritable(styleStack.last() + attrs))
                            transformStack.addLast(compose(transformStack.last(), attrs["transform"], name))
                            opacityStack.addLast(opacityStack.last() * (attrs["opacity"]?.toFloatOrNull() ?: 1f))
                            continue
                        }

                        else -> {
                            val style = styleStack.last() + attrs
                            val path = pathFor(name, attrs)
                            if (path != null && defsDepth < 0) {
                                reportRefusedAttributes(name, attrs)
                                pending += PendingNode(
                                    id = attrs["id"],
                                    path = path,
                                    style = style,
                                    opacity = opacityStack.last() * (attrs["opacity"]?.toFloatOrNull() ?: 1f),
                                    transform = compose(transformStack.last(), attrs["transform"], name),
                                )
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "g") {
                        styleStack.removeLast(); transformStack.removeLast(); opacityStack.removeLast()
                    }
                    if (parser.name == "defs" && defsDepth == depth) defsDepth = -1
                    depth--
                }
            }
        }

        resolvePaints()
        adviseOnGeneratedIds()
        return VectorArt(viewBox, nodes.toList(), warnings.toList())
    }

    // ---------------------------------------------------------------- attributes and style

    /**
     * The element's attributes, with `style="fill:#f00;stroke:none"` merged over the top.
     *
     * Inkscape writes most presentation that way rather than as separate attributes, and the style
     * attribute outranks them in SVG — so merging it last is both correct and what makes real
     * Inkscape output render in colour instead of as black shapes.
     */
    private fun attributesOf(parser: XmlPullParser, element: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (i in 0 until parser.attributeCount) {
            val namespace = parser.getAttributeNamespace(i)
            if (namespace.isNotEmpty() && subset.isIgnoredNamespace(namespace)) continue
            val name = parser.getAttributeName(i)
            if (subset.isIgnoredAttribute(name)) continue
            out[name] = parser.getAttributeValue(i)
        }
        out.remove("style")?.let { declarations ->
            for (declaration in declarations.split(';')) {
                val property = declaration.substringBefore(':').trim()
                val value = declaration.substringAfter(':', "").trim()
                if (property.isEmpty() || value.isEmpty()) continue
                if (property in subset.styleProperties) out[property] = value
                else if (subset.exclusionForAttribute(property) == null &&
                    !subset.allowsAttribute(element, property)
                ) continue
            }
        }
        return out
    }

    /** Presentation properties inherit down the tree; geometry and `id` do not. */
    private fun inheritable(style: Map<String, String>): Map<String, String> =
        style.filterKeys { it in subset.styleProperties && it != "opacity" }

    private fun reportRefusedAttributes(element: String, attrs: Map<String, String>) {
        for ((name, _) in attrs) {
            subset.exclusionForAttribute(name)?.let { excluded ->
                warn(Severity.DEGRADED, describe(element, attrs["id"], name),
                    "${excluded.reason} ${excluded.advice}")
            }
        }
        for (required in subset.requiredAttributes(element)) {
            if (attrs[required] == null) {
                warn(Severity.SKIPPED, describe(element, attrs["id"]),
                    "Has no $required, so there is nothing to draw.")
            }
        }
    }

    // ---------------------------------------------------------------- geometry

    private fun pathFor(element: String, a: Map<String, String>): Path? {
        val path = when (element) {
            "path" -> a["d"]?.let { PathParser().parsePathString(it).toPath() }

            "rect" -> {
                val w = a.number("width"); val h = a.number("height")
                if (w <= 0f || h <= 0f) return null
                val x = a.number("x"); val y = a.number("y")
                // "If rx is absent it is the value of ry, and vice versa" — SVG 1.1 §9.2.
                val rx = (a["rx"] ?: a["ry"])?.toFloatOrNull()?.coerceAtMost(w / 2) ?: 0f
                val ry = (a["ry"] ?: a["rx"])?.toFloatOrNull()?.coerceAtMost(h / 2) ?: 0f
                Path().apply {
                    if (rx > 0f || ry > 0f) {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                Rect(x, y, x + w, y + h),
                                androidx.compose.ui.geometry.CornerRadius(rx, ry),
                            )
                        )
                    } else {
                        addRect(Rect(x, y, x + w, y + h))
                    }
                }
            }

            "circle" -> {
                val r = a.number("r")
                if (r <= 0f) return null
                val cx = a.number("cx"); val cy = a.number("cy")
                Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }
            }

            "ellipse" -> {
                val rx = a.number("rx"); val ry = a.number("ry")
                if (rx <= 0f || ry <= 0f) return null
                val cx = a.number("cx"); val cy = a.number("cy")
                Path().apply { addOval(Rect(cx - rx, cy - ry, cx + rx, cy + ry)) }
            }

            "line" -> Path().apply {
                moveTo(a.number("x1"), a.number("y1")); lineTo(a.number("x2"), a.number("y2"))
            }

            "polygon", "polyline" -> {
                val points = points(a["points"])
                if (points.size < 2) return null
                Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (p in points.drop(1)) lineTo(p.x, p.y)
                    if (element == "polygon") close()
                }
            }

            else -> null
        } ?: return null

        if (a["fill-rule"] == "evenodd") path.fillType = PathFillType.EvenOdd
        return path
    }

    private fun points(raw: String?): List<Offset> {
        val numbers = raw.orEmpty().split(',', ' ', '\n', '\t', '\r')
            .filter { it.isNotBlank() }.mapNotNull { it.trim().toFloatOrNull() }
        return (0 until numbers.size / 2).map { Offset(numbers[it * 2], numbers[it * 2 + 1]) }
    }

    private fun readViewBox(a: Map<String, String>): Rect {
        val parts = a["viewBox"].orEmpty().split(',', ' ').filter { it.isNotBlank() }
            .mapNotNull { it.toFloatOrNull() }
        if (parts.size == 4) return Rect(parts[0], parts[1], parts[0] + parts[2], parts[1] + parts[3])

        val w = a.number("width"); val h = a.number("height")
        warn(Severity.DEGRADED, "<svg>",
            "No viewBox, so the artwork has no defined coordinate space. " +
                if (w > 0f && h > 0f) "Falling back to width and height."
                else "Falling back to a 100 × 100 space, which is almost certainly wrong.")
        return if (w > 0f && h > 0f) Rect(0f, 0f, w, h) else Rect(0f, 0f, 100f, 100f)
    }

    // ---------------------------------------------------------------- transforms

    /**
     * **Compose's `a.timesAssign(b)` computes b · a — apply `a`, then `b` — not a · b.** SVG needs the
     * parent's transform applied *after* the element's own (parent · own), so `own` is the receiver and
     * `parent` the argument. *(Fixed 2026-10-05, found by DASH-AA, the Linux fork: this read
     * `parent.timesAssign(own)`, which applied the parent first. Harmless for a lone translate or a lone
     * scale, which is why the Tank Gauge and `climate_h_large` drew correctly; wrong for any nesting of
     * the two — every glyph the later Climate layouts place with a `translate(…) scale(…)` group landed off
     * the panel. The browser previewer, applying real SVG, never showed it.)*
     */
    private fun compose(parent: Matrix, transform: String?, element: String): Matrix {
        val own = parse(transform, element) ?: return parent
        return Matrix(own.values.copyOf()).apply { timesAssign(parent) }
    }

    /** `translate(320, 60) rotate(-40 150 150)` — applied left to right, as SVG specifies. */
    private fun parse(transform: String?, element: String): Matrix? {
        if (transform.isNullOrBlank()) return null
        val result = Matrix()
        val calls = Regex("""(\w+)\s*\(([^)]*)\)""").findAll(transform)
        for (call in calls) {
            val function = call.groupValues[1]
            val v = call.groupValues[2].split(',', ' ').filter { it.isNotBlank() }
                .mapNotNull { it.trim().toFloatOrNull() }
            if (!subset.allowsTransform(function)) {
                warn(Severity.DEGRADED, "<$element> transform",
                    "`$function()` is not part of the subset. That part of the transform is ignored.")
                continue
            }
            val step = when (function) {
                "translate" -> affine(1f, 0f, 0f, 1f, v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f })
                "scale" -> affine(v.getOrElse(0) { 1f }, 0f, 0f, v.getOrElse(1) { v.getOrElse(0) { 1f } }, 0f, 0f)
                "matrix" -> if (v.size == 6) affine(v[0], v[1], v[2], v[3], v[4], v[5]) else Matrix()
                "rotate" -> {
                    val radians = Math.toRadians(v.getOrElse(0) { 0f }.toDouble())
                    val cos = kotlin.math.cos(radians).toFloat()
                    val sin = kotlin.math.sin(radians).toFloat()
                    val rotation = affine(cos, sin, -sin, cos, 0f, 0f)
                    if (v.size >= 3) {
                        // rotate(a cx cy) is translate(cx cy) rotate(a) translate(-cx -cy) — built
                        // innermost first, since `timesAssign` applies its argument after the receiver
                        // (see compose()).
                        Matrix(affine(1f, 0f, 0f, 1f, -v[1], -v[2]).values.copyOf()).apply {
                            timesAssign(rotation)
                            timesAssign(affine(1f, 0f, 0f, 1f, v[1], v[2]))
                        }
                    } else rotation
                }
                "skewX" -> affine(1f, 0f, kotlin.math.tan(Math.toRadians(v.getOrElse(0) { 0f }.toDouble())).toFloat(), 1f, 0f, 0f)
                "skewY" -> affine(1f, kotlin.math.tan(Math.toRadians(v.getOrElse(0) { 0f }.toDouble())).toFloat(), 0f, 1f, 0f, 0f)
                else -> Matrix()
            }
            // SVG's list reads outermost first — `translate(t) scale(s)` scales, then translates — so each
            // later step applies *before* everything accumulated: step first, then result (see compose()).
            Matrix(step.values.copyOf()).apply { timesAssign(result) }.values.copyInto(result.values)
        }
        return result
    }

    /** SVG's `matrix(a b c d e f)` in Compose's column-major 4×4. */
    private fun affine(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) = Matrix(
        floatArrayOf(
            a, b, 0f, 0f,
            c, d, 0f, 0f,
            0f, 0f, 1f, 0f,
            e, f, 0f, 1f,
        )
    )

    // ---------------------------------------------------------------- paint

    private fun readGradient(parser: XmlPullParser, kind: String, a: Map<String, String>) {
        val id = a["id"]
        val stops = mutableListOf<GradientStop>()
        val depth = parser.depth
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth <= depth) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name != "stop") continue
            val stop = attributesOf(parser, "stop")
            val colour = colour(stop["stop-color"]) ?: Color.Black
            stops += GradientStop(
                offset = stop["offset"]?.toFloatOrNull() ?: 0f,
                colour = colour.copy(alpha = stop["stop-opacity"]?.toFloatOrNull() ?: 1f),
            )
        }
        if (id == null || stops.size < 2) return

        // The gradient's own transform, carrying whatever rotation and scale the author applied in
        // their tool. Applied in gradient space by the renderer; see [GradientSpec.transform].
        val transform = parse(a["gradientTransform"], kind)
        transform?.let { warnIfNotASimilarity(it, describe(kind, id)) }

        val userSpace = a["gradientUnits"] == "userSpaceOnUse"
        gradients[id] = if (kind == "linearGradient") {
            GradientSpec.Linear(
                x1 = a["x1"]?.toFloatOrNull() ?: 0f, y1 = a["y1"]?.toFloatOrNull() ?: 0f,
                x2 = a["x2"]?.toFloatOrNull() ?: 1f, y2 = a["y2"]?.toFloatOrNull() ?: 0f,
                stops = stops, objectBoundingBox = !userSpace, transform = transform,
            )
        } else {
            GradientSpec.Radial(
                cx = a["cx"]?.toFloatOrNull() ?: 0.5f, cy = a["cy"]?.toFloatOrNull() ?: 0.5f,
                r = a["r"]?.toFloatOrNull() ?: 0.5f,
                stops = stops, objectBoundingBox = !userSpace, transform = transform,
            )
        }
    }

    /**
     * A gradient transform is honoured by moving the gradient's control points through it, which is
     * **exact for translation, rotation and uniform scale** — the rotation case `module-layout.md`
     * §9 requires, and the overwhelming majority of what a tool writes.
     *
     * It is an approximation for a non-uniform scale or a skew, because the lines of constant colour
     * would no longer stay square to the gradient's axis, and Compose's `Brush` takes control points
     * rather than a matrix. An elliptical radial gradient drawn round instead is a real difference
     * the author can see, so it is said out loud rather than left to be discovered.
     */
    private fun warnIfNotASimilarity(m: Matrix, what: String) {
        val a = m.values[0]; val b = m.values[1]; val c = m.values[4]; val d = m.values[5]
        val scale = maxOf(kotlin.math.hypot(a, b), kotlin.math.hypot(c, d))
        if (scale <= 0f) return
        val skewed = kotlin.math.abs(a - d) > scale * 0.01f || kotlin.math.abs(b + c) > scale * 0.01f
        if (skewed) {
            warn(Severity.DEGRADED, what,
                "Its gradientTransform stretches or skews the gradient. DASH applies the rotation " +
                    "and the overall scale, so the gradient runs along the right axis, but it is " +
                    "drawn round rather than stretched. Flatten it at export if the shape matters.")
        }
    }

    private fun resolvePaints() {
        for (p in pending) {
            // SVG's initial fill is black; its initial stroke is none. A shape with no fill stated
            // is a black shape, which is exactly why the style attribute has to be read.
            val fill = paint(p.style["fill"] ?: "#000000", p.style["fill-opacity"], p.id)
            val stroke = paint(p.style["stroke"], p.style["stroke-opacity"], p.id)
            nodes += ArtNode(
                id = p.id,
                path = p.path,
                bounds = p.path.getBounds(),
                fill = fill,
                stroke = stroke,
                strokeWidth = p.style["stroke-width"]?.toFloatOrNull() ?: 1f,
                strokeCap = when (p.style["stroke-linecap"]) {
                    "round" -> StrokeCap.Round; "square" -> StrokeCap.Square; else -> StrokeCap.Butt
                },
                strokeJoin = when (p.style["stroke-linejoin"]) {
                    "round" -> StrokeJoin.Round; "bevel" -> StrokeJoin.Bevel; else -> StrokeJoin.Miter
                },
                opacity = p.opacity,
                transform = p.transform,
            )
        }
    }

    private fun paint(value: String?, opacity: String?, id: String?): ArtPaint? {
        if (value == null || value == "none") return null
        val alpha = opacity?.toFloatOrNull() ?: 1f
        if (value.startsWith("url(")) {
            val reference = value.removePrefix("url(").removeSuffix(")").trim().removePrefix("#")
            val gradient = gradients[reference]
            if (gradient == null) {
                warn(Severity.DEGRADED, describe("paint", id),
                    "References `$reference`, which is not a gradient in this file. Drawn plain grey.")
                return ArtPaint.Solid(Color(0xFF808080), alpha)
            }
            return ArtPaint.Gradient(gradient, alpha)
        }
        val colour = colour(value)
        if (colour == null) {
            warn(Severity.DEGRADED, describe("paint", id), "`$value` is not a colour DASH understands.")
            return null
        }
        return ArtPaint.Solid(colour, alpha)
    }

    private fun colour(value: String?): Color? {
        val v = value?.trim() ?: return null
        return when {
            v == "none" -> null
            v == "currentColor" -> Color.Black
            // `parseColor` throws on anything it dislikes, and this is data arriving from a device
            // DASH does not control. A malformed colour is a wrong-looking element, never a crash.
            v.startsWith("#") -> runCatching {
                when (v.length) {
                    4 -> Color(android.graphics.Color.parseColor("#${v[1]}${v[1]}${v[2]}${v[2]}${v[3]}${v[3]}"))
                    7 -> Color(android.graphics.Color.parseColor(v))
                    9 -> Color(android.graphics.Color.parseColor(v))
                    else -> null
                }
            }.getOrNull()
            v.startsWith("rgb(") -> {
                val parts = v.removePrefix("rgb(").removeSuffix(")").split(',')
                    .mapNotNull { part ->
                        val t = part.trim()
                        if (t.endsWith("%")) t.dropLast(1).toFloatOrNull()?.let { it * 255f / 100f }
                        else t.toFloatOrNull()
                    }
                if (parts.size == 3) {
                    Color(
                        parts[0].toInt().coerceIn(0, 255),
                        parts[1].toInt().coerceIn(0, 255),
                        parts[2].toInt().coerceIn(0, 255),
                    )
                } else null
            }
            else -> subset.namedColour(v)
        }
    }

    // ---------------------------------------------------------------- housekeeping

    /**
     * One quiet line rather than one per element.
     *
     * Inkscape gives every object an id whether the author named it or not — `path4`, `line11` —
     * so an element without an id barely exists in real output. The author's actual problem is the
     * opposite: ids that exist, mean nothing, and change on the next export. Warning per element
     * would bury everything else, so this counts them and says it once.
     */
    private fun adviseOnGeneratedIds() {
        val generated = Regex("""^(path|rect|circle|ellipse|line|polygon|polyline|g|tspan|text|use|image|svg|stop)\d+$""")
        val count = nodes.count { it.id != null && generated.matches(it.id) }
        if (count == 0) return
        warn(Severity.ADVICE, "$count element${if (count == 1) "" else "s"}",
            "Carry a tool-generated id (path4, line11). They are drawn, but an id like that changes " +
                "on the next export, so nothing should bind to it. Name anything a binding targets " +
                "in Inkscape's Object Properties.")
    }

    private fun describe(element: String, id: String?, attribute: String? = null): String =
        buildString {
            append('<'); append(element); append('>')
            if (id != null) append(" #$id")
            if (attribute != null) append(" $attribute")
        }

    private fun warn(severity: Severity, what: String, message: String) {
        warnings += Warning(severity, what, message)
    }

    private fun skipSubtree(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun Map<String, String>.number(key: String): Float =
        this[key]?.trim()?.removeSuffix("px")?.toFloatOrNull() ?: 0f
}
