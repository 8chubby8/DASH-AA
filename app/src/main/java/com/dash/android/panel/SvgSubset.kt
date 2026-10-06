package com.dash.android.panel

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The SVG subset DASH renders, loaded from data rather than written into the parser.
 *
 * **This is the previewer's Prime Directive made real.** The subset is defined once, in
 * `svg-subset.json` at the repository root, and read by both this parser and (later)
 * `panel-preview`. Nothing here hardcodes an element name or an attribute name. If the subset
 * grows, this file does not change — which is the whole point, because a subset maintained by
 * hand in two places diverges, silently, in the direction that hurts most: a previewer showing
 * something the app cannot draw.
 *
 * The exclusion **messages** live in the data too, not just the lists. An author who is told
 * *"`<filter>` — DASH draws the element unfiltered; bake the effect into the artwork at export"*
 * has been given somewhere to go. One told *"unsupported element"* has been given a shrug.
 */
class SvgSubset(private val root: JsonObject) {

    /** Why something was refused, and what the author should do instead. Both come from the data. */
    data class Exclusion(val reason: String, val advice: String)

    private fun obj(vararg path: String): JsonObject? {
        var here: JsonObject? = root
        for (key in path) here = here?.get(key)?.jsonObject
        return here
    }

    private fun strings(vararg path: String): Set<String> {
        val parent = if (path.size > 1) obj(*path.dropLast(1).toTypedArray()) else root
        val array = parent?.get(path.last()) as? kotlinx.serialization.json.JsonArray ?: return emptySet()
        return array.map { it.jsonPrimitive.content }.toSet()
    }

    private val elements = obj("elements")
    private val excludedElements = obj("excluded", "elements")
    private val excludedAttributes = obj("excluded", "attributes")
    private val namedColours = obj("colours", "named")

    private val globalAttributes = strings("attributes", "global")
    private val presentationAttributes = strings("attributes", "presentation")
    private val transformFunctions = strings("transforms")
    private val ignoredNamespaces = strings("ignored", "namespaces")
    private val ignoredElements = strings("ignored", "elements")
    private val ignoredAttributes = strings("ignored", "attributes")

    val version: Int = root["version"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

    /** Properties readable from a `style="…"` attribute — the same list, in Inkscape's preferred form. */
    val styleProperties: Set<String> = strings("attributes", "style", "properties")

    fun allowsElement(name: String): Boolean = elements?.containsKey(name) == true

    fun allowsAttribute(element: String, attribute: String): Boolean {
        if (attribute in globalAttributes || attribute in presentationAttributes) return true
        val spec = elements?.get(element)?.jsonObject ?: return false
        val geometry = (spec["geometry"] as? kotlinx.serialization.json.JsonArray)
            ?.map { it.jsonPrimitive.content } ?: emptyList()
        val properties = (spec["properties"] as? kotlinx.serialization.json.JsonArray)
            ?.map { it.jsonPrimitive.content } ?: emptyList()
        return attribute in geometry || attribute in properties
    }

    fun requiredAttributes(element: String): List<String> =
        (elements?.get(element)?.jsonObject?.get("requires") as? kotlinx.serialization.json.JsonArray)
            ?.map { it.jsonPrimitive.content } ?: emptyList()

    fun allowsTransform(function: String): Boolean = function in transformFunctions

    fun exclusionForElement(name: String): Exclusion? = exclusion(excludedElements, name)

    fun exclusionForAttribute(name: String): Exclusion? = exclusion(excludedAttributes, name)

    private fun exclusion(from: JsonObject?, name: String): Exclusion? {
        val spec = from?.get(name)?.jsonObject ?: return null
        return Exclusion(
            reason = spec["reason"]?.jsonPrimitive?.content.orEmpty(),
            advice = spec["advice"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    /**
     * Noise, not error. Every file Inkscape has ever written carries `sodipodi:namedview` and a
     * `metadata` block; warning about them would bury the warnings that matter.
     */
    fun isIgnoredNamespace(uri: String): Boolean = uri in ignoredNamespaces
    fun isIgnoredElement(name: String): Boolean = name in ignoredElements
    fun isIgnoredAttribute(name: String): Boolean = name in ignoredAttributes

    /** The 147 CSS colour names. Android's own `Color.parseColor` knows about fifteen of them. */
    fun namedColour(name: String): Color? {
        val hex = namedColours?.get(name.lowercase())?.jsonPrimitive?.content ?: return null
        return Color(android.graphics.Color.parseColor(hex))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Copied into assets from the repository root at build time — see `app/build.gradle.kts`. */
        fun load(context: Context): SvgSubset {
            val text = context.assets.open("svg-subset.json").bufferedReader().use { it.readText() }
            return SvgSubset(json.parseToJsonElement(text).jsonObject)
        }
    }
}
