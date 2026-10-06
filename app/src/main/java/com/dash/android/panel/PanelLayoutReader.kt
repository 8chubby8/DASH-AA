package com.dash.android.panel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The nine DASH theme tokens a layout may delegate a colour to (`module-layout.md` §7).
 *
 * Written without the `@`, which is the prefix that distinguishes a token from a literal on the
 * wire. The four `bar*` tokens some older documents offered as examples **do not exist** — they
 * were retired at roadmap 1.5.2, and a module written against them was written against a
 * vocabulary DASH had already discarded, which is exactly the kind of silent mismatch the reader
 * below warns about rather than swallowing.
 */
val DASH_THEME_TOKENS = setOf(
    "backgroundColourPrimary", "backgroundColourSecondary",
    "textColourPrimary", "textColourSecondary",
    "iconColourPrimary", "iconColourSecondary",
    "accentColourPrimary", "accentColourSecondary",
    "font",
)

/**
 * Reads a layout document into a [PanelLayout] (roadmap 1.6.6).
 *
 * **Nothing in here throws, and nothing here is fatal.** This is the browser rule (§9) applied to
 * the layout as the SVG parser applies it to the artwork: DASH renders what it understands and
 * ignores what it does not, so an author who used something outside the format gets a panel missing
 * one flourish and a warning naming it — never a dead panel and never an error. That is what lets
 * the format grow across versions without breaking a module already installed in someone's vehicle.
 *
 * **Why the JSON tree rather than `@Serializable` classes.** Decoding straight into data classes
 * would give the tidier code and the wrong behaviour: one mistyped field — `"opacity": "loud"` —
 * would fail the whole document and blank the panel, and an unknown layer `type` would throw rather
 * than being skipped. Reading the tree by hand keeps every failure local to the thing that failed,
 * which is the difference between a panel with one wrong element and no panel at all. It is also
 * data arriving from a device DASH does not control, which is reason enough on its own.
 */
class PanelLayoutReader {

    private val warnings = mutableListOf<Warning>()

    fun read(source: String): PanelLayout {
        warnings.clear()

        val root = runCatching { json.parseToJsonElement(source).jsonObject }.getOrNull()
            ?: return PanelLayout(
                warnings = listOf(
                    Warning(
                        Severity.SKIPPED, "the layout",
                        "Is not a JSON object, so nothing in it could be read. The document should " +
                            "be `{ \"layers\": [ … ], \"bindings\": [ … ] }`.",
                    )
                )
            )

        val layers = root.array("layers").mapIndexedNotNull { i, element ->
            element.asObject("layer $i")?.let { layer(it, i) }
        }
        val known = layers.map { it.id }.toSet()
        val bindings = root.array("bindings").mapIndexedNotNull { i, element ->
            element.asObject("binding $i")?.let { binding(it, i, known) }
        }

        val variables = variables(root.obj("variables"))

        adviseOnUnusedLayers(layers, bindings)
        adviseOnSteppingUndeclared(bindings, variables)
        return PanelLayout(layers, bindings, variables, warnings.toList())
    }

    /**
     * The module's private message board (§8a) — a name to an ordered list of values.
     *
     * **Absent is normal and means nothing is predicted.** A module that declares no board behaves
     * exactly as every module did before 1.6.8: presses go out, values change when the module
     * answers. The browser rule, applied to a whole feature rather than one field.
     */
    private fun variables(o: JsonObject?): Map<String, PanelVariable> {
        if (o == null) return emptyMap()
        return o.entries.mapNotNull { (name, element) ->
            val body = element.asObject("variable `$name`") ?: return@mapNotNull null
            // Values are read as raw strings, never as numbers, because the string IS the contract:
            // it is what DASH sends in an ACTION and what it matches the module's REPORT against.
            // Reading `17` as a float and printing it back would send `17.0` and never match.
            val values = body.array("values").mapNotNull { it.asString() }
            if (values.isEmpty()) {
                warn(
                    Severity.SKIPPED, "variable `$name`",
                    "Has no `values`, so DASH has no list to move along and cannot draw a press to " +
                        "it before the module answers. Give it every value it takes, in order.",
                )
                return@mapNotNull null
            }
            if (values.size != values.toSet().size) {
                warn(
                    Severity.DEGRADED, "variable `$name`",
                    "Lists the same value twice. DASH steps from the first match, so the later copy " +
                        "can never be reached by pressing.",
                )
            }
            name to PanelVariable(values = values, wrap = body.bool("wrap") ?: false)
        }.toMap()
    }

    /**
     * A `step` control naming a variable with no declared list.
     *
     * Worth saying out loud rather than silently degrading: the panel still works — the press is
     * sent and the module still answers — but it will feel a round trip slower than every other
     * control on the panel, and the author's own layout is the only place that can say why.
     */
    private fun adviseOnSteppingUndeclared(bindings: List<PanelBinding>, variables: Map<String, PanelVariable>) {
        bindings.filterIsInstance<TouchBinding>()
            .filter { it.step != 0 && it.control !in variables }
            .map { it.control }
            .distinct()
            .forEach { control ->
                warn(
                    Severity.DEGRADED, "control `$control`",
                    "Is stepped by a touch binding but has no entry in `variables`, so DASH does not " +
                        "know what its next value would be. The press is still sent and the module " +
                        "still answers — it simply cannot be drawn until it does.",
                )
            }
    }

    // -------------------------------------------------------------------------------- layers

    private fun layer(o: JsonObject, index: Int): PanelLayer? {
        val id = o.string("id")
        if (id.isNullOrBlank()) {
            warn(Severity.SKIPPED, "layer $index", "Has no `id`, so no binding could ever point at it.")
            return null
        }
        val opacity = o.float("opacity")?.coerceIn(0f, 1f) ?: 1f

        return when (val type = o.string("type")?.trim()?.lowercase()) {
            "raster", "vector" -> {
                val asset = o.string("asset")
                if (asset.isNullOrBlank()) {
                    warn(Severity.SKIPPED, "layer #$id", "Is a $type layer with no `asset`, so there is nothing to draw.")
                    return null
                }
                val at = o.offset("at")
                val size = o.offset("size")?.let { Size(it.x, it.y) }
                val pivot = o.offset("pivot") ?: Offset(0.5f, 0.5f)
                if (type == "raster") RasterLayer(id, asset, at, size, pivot, opacity)
                else VectorLayer(id, asset, at, size, pivot, opacity)
            }

            "text" -> {
                val value = o.string("value")
                if (value.isNullOrBlank()) {
                    warn(Severity.SKIPPED, "layer #$id", "Is a text layer with no `value`, so it names no variable to print.")
                    return null
                }
                val at = o.offset("at")
                if (at == null) {
                    warn(Severity.SKIPPED, "layer #$id", "Is a text layer with no `at`, so DASH has nowhere to draw it. Text has an anchor, not a box.")
                    return null
                }
                val fontSize = o.float("fontSize")
                if (fontSize == null || fontSize <= 0f) {
                    warn(Severity.SKIPPED, "layer #$id", "Is a text layer with no usable `fontSize`. It is a fraction of the panel's height, so 0.1 is a tenth of the panel.")
                    return null
                }
                TextLayer(
                    id = id,
                    value = value,
                    at = at,
                    fontSize = fontSize,
                    align = when (o.string("align")?.trim()?.lowercase()) {
                        "center", "centre" -> TextAlign.CENTER
                        "right" -> TextAlign.RIGHT
                        null, "left" -> TextAlign.LEFT
                        else -> {
                            warn(Severity.DEGRADED, "layer #$id", "`align` is not left, center or right. Drawn left-aligned.")
                            TextAlign.LEFT
                        }
                    },
                    colour = o.colour("colour", "layer #$id") ?: ColourRef.Token("textColourPrimary"),
                    font = font(o.string("font"), "layer #$id"),
                    bold = o.string("weight")?.trim()?.lowercase() == "bold",
                    italic = o.bool("italic") ?: false,
                    decimals = o.int("decimals")?.coerceIn(0, 8),
                    prefix = o.string("prefix").orEmpty(),
                    suffix = o.string("suffix").orEmpty(),
                    opacity = opacity,
                )
            }

            null -> {
                warn(Severity.SKIPPED, "layer #$id", "Has no `type`. It must be raster, vector or text.")
                null
            }

            else -> {
                warn(
                    Severity.SKIPPED, "layer #$id",
                    "Has type `$type`, which DASH does not draw. The three layer types are raster, " +
                        "vector and text.",
                )
                null
            }
        }
    }

    private fun font(raw: String?, what: String): PanelFont = when {
        raw == null -> PanelFont.DashToken
        raw.trim() == "@font" -> PanelFont.DashToken
        raw.startsWith("@") -> {
            warn(Severity.DEGRADED, what, "`$raw` is not a DASH token. The only font token is `@font`. Using it.")
            PanelFont.DashToken
        }
        // Anything else is attempted and falls back to the device default if it is not there — the
        // browser rule again, and necessary because what a vendor ships varies between a consumer
        // tablet and a bare head-unit image.
        else -> PanelFont.Family(raw.trim())
    }

    // ------------------------------------------------------------------------------ bindings

    private fun binding(o: JsonObject, index: Int, layers: Set<String>): PanelBinding? {
        val kind = o.string("bind")?.trim()?.lowercase()
        val target = target(o, index, layers) ?: return null
        val ease = o.int("ease")?.coerceAtLeast(0) ?: 0
        val what = "binding $index ($target)"

        return when (kind) {
            "style" -> {
                val value = o.string("value")
                val cases = o.array("cases").mapNotNull { it.asObject("$what case")?.let(::styleCase) }
                if (value == null && cases.isNotEmpty()) {
                    warn(Severity.DEGRADED, what, "Has `cases` but no `value`, so there is no variable to match them against. The cases are ignored and it acts as a static restyle.")
                }
                StyleBinding(
                    target = target,
                    variable = value,
                    cases = if (value == null) emptyList() else cases,
                    // With no variable the binding's own properties *are* the effect — that is §4.3's
                    // static restyle, and it is how a token is delegated to an element.
                    default = o.obj("default")?.let(::styleEffect) ?: styleEffect(o),
                    ease = ease,
                )
            }

            "reveal" -> {
                val value = o.string("value") ?: return warnMissing(what, "value")
                val from = o.range("from") ?: return warnMissing(what, "from")
                val direction = RevealDirection.from(o.string("direction"))
                if (o.string("direction") != null && direction == null) {
                    warn(Severity.DEGRADED, what, "`direction` is not up, down, left or right. Filling up.")
                }
                RevealBinding(target, value, from, direction ?: RevealDirection.UP, ease)
            }

            "rotate" -> {
                val value = o.string("value") ?: return warnMissing(what, "value")
                val from = o.range("from") ?: return warnMissing(what, "from")
                val to = o.range("to") ?: return warnMissing(what, "to")
                RotateBinding(target, value, from, to, o.offset("pivot"), ease)
            }

            "translate" -> {
                val value = o.string("value") ?: return warnMissing(what, "value")
                val from = o.range("from") ?: return warnMissing(what, "from")
                val pair = o.array("to").mapNotNull { it.asOffset() }
                if (pair.size != 2) return warnMissing(what, "to")
                TranslateBinding(target, value, from, pair[0], pair[1], ease)
            }

            "touch" -> {
                val control = o.string("control") ?: return warnMissing(what, "control")
                val step = o.int("step") ?: 0
                if (step != 0 && o.string("value") != null) {
                    warn(
                        Severity.DEGRADED, what,
                        "Has both `step` and `value`. A control either names the value it sets or " +
                            "moves along a list; it cannot do both. Stepping.",
                    )
                }
                TouchBinding(
                    target = target,
                    control = control,
                    value = if (step != 0) "" else o.string("value").orEmpty(),
                    step = step,
                    feedback = o.obj("feedback")?.let(::styleEffect),
                    ease = ease,
                )
            }

            null -> {
                warn(Severity.SKIPPED, what, "Has no `bind`, so DASH does not know what it should do.")
                null
            }

            else -> {
                warn(
                    Severity.SKIPPED, what,
                    "`$kind` is not a binding DASH knows. The five are touch, style, reveal, rotate " +
                        "and translate.",
                )
                null
            }
        }
    }

    /**
     * `target` names a layer or a `layer#element`; `rect` is a bare area attached to no layer.
     *
     * A target naming a layer the document never declared is reported **per occurrence** — unlike
     * the tool-generated-id advice, this is a genuine mistake rather than a habit, and it is exactly
     * the case that otherwise costs an author an afternoon.
     */
    private fun target(o: JsonObject, index: Int, layers: Set<String>): BindingTarget? {
        o.string("target")?.let { raw ->
            val layer = raw.substringBefore('#').trim()
            val element = raw.substringAfter('#', "").trim().ifBlank { null }
            if (layer.isEmpty()) {
                warn(Severity.SKIPPED, "binding $index", "Has an empty `target`.")
                return null
            }
            if (layer !in layers) {
                warn(
                    Severity.SKIPPED, "binding $index",
                    "Targets `$raw`, but this layout has no layer called `$layer`. Check the spelling " +
                        "against the layer's `id`.",
                )
                return null
            }
            return BindingTarget.Named(layer, element)
        }

        o.array("rect").mapNotNull { (it.jsonPrimitiveOrNull())?.floatOrNull }.let { r ->
            if (r.size == 4) return BindingTarget.Area(Rect(r[0], r[1], r[0] + r[2], r[1] + r[3]))
        }

        warn(Severity.SKIPPED, "binding $index", "Names neither a `target` nor a `rect`, so it points at nothing.")
        return null
    }

    private fun styleCase(o: JsonObject): StyleCase? {
        val match = when {
            o.contains("is") -> ValueMatch.Is(o.string("is").orEmpty())
            o.contains("below") -> o.float("below")?.let { ValueMatch.Below(it) }
            o.contains("above") -> o.float("above")?.let { ValueMatch.Above(it) }
            o.contains("between") -> o.range("between")?.let { ValueMatch.Between(it.start, it.endInclusive) }
            else -> null
        }
        if (match == null) {
            warn(Severity.SKIPPED, "a style case", "Matches on nothing. A case needs one of `is`, `below`, `above` or `between`.")
            return null
        }
        return StyleCase(match, styleEffect(o))
    }

    private fun styleEffect(o: JsonObject) = StyleEffect(
        colour = o.colour("colour", "a style effect"),
        opacity = o.float("opacity")?.coerceIn(0f, 1f),
        pulse = o.int("pulse")?.takeIf { it > 0 },
    )

    // ----------------------------------------------------------------------------- utilities

    /**
     * A layer nothing binds to and nothing fills is not an error — a painted background is exactly
     * that — so this says nothing about layers. It exists for the reverse case only.
     */
    private fun adviseOnUnusedLayers(layers: List<PanelLayer>, bindings: List<PanelBinding>) {
        if (layers.isEmpty()) {
            warn(Severity.SKIPPED, "the layout", "Declares no layers, so the panel would draw nothing.")
        }
        val dynamic = layers.any { it is TextLayer } || bindings.any { it.variable != null }
        if (layers.isNotEmpty() && !dynamic) {
            warn(
                Severity.ADVICE, "the layout",
                "Draws artwork but watches no module variable, so the panel will never change. That " +
                    "is valid — but if it was meant to move, check the bindings.",
            )
        }
    }

    private fun warnMissing(what: String, field: String): PanelBinding? {
        warn(Severity.SKIPPED, what, "Has no `$field`, which that binding cannot work without.")
        return null
    }

    private fun warn(severity: Severity, what: String, message: String) {
        warnings += Warning(severity, what, message)
    }

    // JSON access that answers "null" to everything it dislikes, so a malformed field costs the
    // author that field and nothing else.

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.float(key: String): Float? =
        (this[key] as? JsonPrimitive)?.let { it.floatOrNull ?: it.contentOrNull?.toFloatOrNull() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.array(key: String): List<kotlinx.serialization.json.JsonElement> =
        (this[key] as? JsonArray)?.toList() ?: emptyList()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /** `[x, y]`. Used for `at`, `size` and `pivot`, which share the shape but not the meaning. */
    private fun JsonObject.offset(key: String): Offset? =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitiveOrNull()?.floatOrNull }
            ?.takeIf { it.size == 2 }?.let { Offset(it[0], it[1]) }

    /** `[min, max]`, tolerated in either order so an inverted gauge range still reads. */
    private fun JsonObject.range(key: String): ClosedFloatingPointRange<Float>? =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitiveOrNull()?.floatOrNull }
            ?.takeIf { it.size == 2 }?.let { it[0]..it[1] }

    /**
     * A literal or a token (§7). **Where there is any doubt, the literal wins** — DASH may never
     * infer a delegation the author did not make, which is the boundary that keeps "the king
     * permitted discretion" from becoming a licence for DASH to form opinions.
     */
    private fun JsonObject.colour(key: String, what: String): ColourRef? {
        val raw = string(key)?.trim() ?: return null
        if (raw.startsWith("@")) {
            val name = raw.removePrefix("@")
            if (name !in DASH_THEME_TOKENS) {
                warn(
                    Severity.DEGRADED, what,
                    "`$raw` is not a DASH theme token, so nothing is delegated and the element keeps " +
                        "its own colour. The tokens are ${DASH_THEME_TOKENS.joinToString(", ") { "@$it" }}.",
                )
                return null
            }
            return ColourRef.Token(name)
        }
        val parsed = runCatching {
            when (raw.length) {
                4 -> Color(android.graphics.Color.parseColor("#${raw[1]}${raw[1]}${raw[2]}${raw[2]}${raw[3]}${raw[3]}"))
                7, 9 -> Color(android.graphics.Color.parseColor(raw))
                else -> null
            }
        }.getOrNull()
        if (parsed == null) {
            warn(Severity.DEGRADED, what, "`$raw` is not a colour DASH understands. Colours are `#RRGGBB` or a `@token`.")
            return null
        }
        return ColourRef.Literal(parsed)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? =
        this as? JsonPrimitive

    private fun kotlinx.serialization.json.JsonElement.asOffset(): Offset? =
        (this as? JsonArray)?.mapNotNull { it.jsonPrimitiveOrNull()?.floatOrNull }
            ?.takeIf { it.size == 2 }?.let { Offset(it[0], it[1]) }

    /**
     * A board value, kept exactly as written.
     *
     * **The string is the contract.** It is what DASH puts in an `ACTION` and what it matches the
     * module's `REPORT` against, so `17` must stay `"17"` — read as a number and printed back it
     * would leave as `17.0` and never match anything the module said.
     */
    private fun kotlinx.serialization.json.JsonElement.asString(): String? =
        jsonPrimitiveOrNull()?.contentOrNull?.trim()?.ifBlank { null }

    private fun kotlinx.serialization.json.JsonElement.asObject(what: String): JsonObject? {
        val o = this as? JsonObject
        if (o == null) warn(Severity.SKIPPED, what, "Is not an object, so there was nothing to read.")
        return o
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
