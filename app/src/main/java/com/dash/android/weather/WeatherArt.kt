package com.dash.android.weather

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Resolves the art for each weather layer by name, so a user can drop in their own pictures (roadmap
 * 1.5.4, the SDKable principle made concrete). Every layer is looked up in three tiers, most-owned
 * first:
 *
 *   1. **User art** — files the user copied into `Android/data/com.dash.android/files/weather/`.
 *      That directory needs no permission to write to from a file manager or over USB (adb push), so
 *      a user owns the look of the scene without DASH ever asking for storage access. The user's car,
 *      the user's art.
 *   2. **Bundled art** — anything shipped inside the APK under `assets/weather/` (what the project
 *      artist delivers). Absent today; the slots are ready for it.
 *   3. **Procedural** — if neither tier has a file, [WeatherArt] returns `null` and the scene draws
 *      that layer in code. The scene is therefore never broken by a missing file, and looks complete
 *      with zero art shipped at all.
 *
 * ### Naming scheme (extension any of `.webp` / `.png` / `.jpg`)
 *
 * Matches the finalised four-layer design — the skybox carries *time only*, the clouds carry the
 * condition's weight, and the background has three graded states.
 *
 * | Layer      | Base name              | Values                                                              |
 * |------------|------------------------|--------------------------------------------------------------------|
 * | Skybox     | `sky-<time>`           | dawn · sunrise · morning · midday · afternoon · evening · sunset · dusk · night |
 * | Clouds     | `clouds-<level>`       | 1 (lightest) … 7 (full overcast)                                   |
 * | Background | `background-<state>`   | day · night · snow                                                 |
 *
 * Foreground weather — rain, snow, fog — is procedural and takes no art. Supply as many or as few
 * files as you like; any layer with no file falls back to the procedural render.
 */
class WeatherArt(context: Context) {

    private val assetManager = context.assets
    private val userDir: File? = context.getExternalFilesDir(USER_DIR)

    // Resolving decodes a file from disk, so cache by base name. Absent files cache as null too, so a
    // missing look isn't probed on every frame.
    private val cache = HashMap<String, ImageBitmap?>()

    fun sky(time: TimeOfDay): ImageBitmap? = load("sky-${time.tag()}")

    fun clouds(level: Int): ImageBitmap? = load("clouds-$level")

    fun background(state: BackgroundState): ImageBitmap? = load("background-${state.tag()}")

    /** First candidate that resolves in any tier wins; null if none do (→ procedural). */
    private fun load(vararg baseNames: String): ImageBitmap? {
        for (base in baseNames) {
            if (cache.containsKey(base)) {
                cache[base]?.let { return it }
                continue
            }
            val bitmap = loadUser(base) ?: loadAsset(base)
            cache[base] = bitmap
            if (bitmap != null) return bitmap
        }
        return null
    }

    private fun loadUser(base: String): ImageBitmap? {
        val dir = userDir ?: return null
        for (ext in EXTENSIONS) {
            val file = File(dir, "$base.$ext")
            if (file.isFile) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath) }
                    .getOrNull()?.let { return it.asImageBitmap() }
            }
        }
        return null
    }

    private fun loadAsset(base: String): ImageBitmap? {
        for (ext in EXTENSIONS) {
            val path = "$USER_DIR/$base.$ext"
            runCatching {
                assetManager.open(path).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { return it.asImageBitmap() }
        }
        return null
    }

    companion object {
        private const val USER_DIR = "weather"
        private val EXTENSIONS = listOf("webp", "png", "jpg")
    }
}

private fun TimeOfDay.tag(): String = name.lowercase()
private fun BackgroundState.tag(): String = name.lowercase()
