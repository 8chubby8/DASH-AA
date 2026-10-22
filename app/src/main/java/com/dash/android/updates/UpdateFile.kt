package com.dash.android.updates

import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * **A DASH update file** (DASH-AA 1.2.2) — `dash_<version>.update`, as `distro/build.sh` makes it: a plain tar
 * whose first two parts are `release.txt` (what the release is, and a checksum for each of its parts) and
 * `release.sig` (the seal on it, made with the release key that lives only on the computer that builds).
 *
 * DASH reads only those two, to show what the release is and whether the seal is good. It is never what
 * decides: the system's own installer (`/usr/lib/dash/dash-update`) checks the seal again, and every part of
 * the file against it, before anything is written. DASH checks so that a file it would refuse is never offered.
 *
 * **For native:** take as it is if native ever installs sealed releases of its own; today it does not apply
 * (Android updates the app itself).
 */
data class Release(
    val version: String,
    val dashVersion: String?,
    val date: String?,
    val summary: List<String>,
    /** This release keeps its settings differently: going back past it means going back to the settings from before. */
    val settingsChanged: Boolean,
    /** How much is written: the system unpacked. */
    val systemBytes: Long?,
)

sealed interface UpdateCheck {
    data class Sealed(val release: Release) : UpdateCheck
    /** Readable, but not sealed by this system's release key: shown, never installed. */
    data class NotSealed(val release: Release?, val why: String) : UpdateCheck
    data class Unreadable(val why: String) : UpdateCheck
}

object UpdateFile {
    private val BUILT = Regex("""^(.+)-(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})$""")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /**
     * A release as people read it. The version is three numbers (Roger, 2026-10-22); each build also carries when
     * it was made (`1.2.2-202610222310`), so a second build of one version can be installed over the first.
     */
    fun shown(release: String): String = BUILT.matchEntire(release)?.destructured?.let { (v, _, mo, d, h, mi) ->
        "DASH $v, built ${d.toInt()} ${MONTHS.getOrElse(mo.toInt() - 1) { mo }} $h:$mi"
    } ?: "DASH $release"

    /** The two parts at the front of the file, or null if they are not there. */
    internal fun head(input: InputStream): Pair<ByteArray, ByteArray?>? {
        val parts = mutableMapOf<String, ByteArray>()
        val header = ByteArray(512)
        while (parts.size < 2) {
            if (input.readNBytes(header, 0, 512) < 512 || header.all { it == 0.toByte() }) break
            val name = String(header, 0, 100, Charsets.US_ASCII).substringBefore('\u0000')
            val size = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLongOrNull(8) ?: return null
            if (name != "release.txt" && name != "release.sig") break
            if (size > 1 shl 20) return null
            val body = input.readNBytes(size.toInt())
            if (body.size.toLong() != size) return null
            input.skipNBytes((512 - size % 512) % 512)
            parts[name] = body
        }
        val text = parts["release.txt"] ?: return null
        return text to parts["release.sig"]
    }

    internal fun parse(text: String): Release? {
        val lines = text.lines().filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
        fun one(k: String) = lines.firstOrNull { it.first == k }?.second?.trim()?.takeIf { it.isNotEmpty() }
        val version = one("version") ?: return null
        return Release(
            version = version,
            dashVersion = one("dash"),
            date = one("date"),
            summary = lines.filter { it.first == "summary" }.map { it.second.trim() }.filter { it.isNotEmpty() },
            settingsChanged = one("settings") == "changed",
            systemBytes = one("system")?.split(' ')?.getOrNull(3)?.toLongOrNull(),
        )
    }

    /** The release key, from the PEM file `openssl pkey -pubout` writes. */
    fun key(pem: String): PublicKey? = runCatching {
        val body = pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
    }.getOrNull()

    fun sealed(text: ByteArray, seal: ByteArray, key: PublicKey): Boolean = runCatching {
        Signature.getInstance("Ed25519").run { initVerify(key); update(text); verify(seal) }
    }.getOrDefault(false)

    fun check(file: File, key: PublicKey?): UpdateCheck {
        val head = runCatching { file.inputStream().buffered().use { head(it) } }.getOrNull()
            ?: return UpdateCheck.Unreadable("This is not a DASH update file.")
        val (text, seal) = head
        val release = parse(String(text, Charsets.UTF_8))
            ?: return UpdateCheck.Unreadable("This update file does not say what it is.")
        return when {
            key == null -> UpdateCheck.NotSealed(release, "This system has no release key, so no update can be checked.")
            seal == null -> UpdateCheck.NotSealed(release, "It has no seal. DASH only installs sealed releases.")
            !sealed(text, seal, key) -> UpdateCheck.NotSealed(release, "Its seal is not DASH's — it may have been changed.")
            else -> UpdateCheck.Sealed(release)
        }
    }
}
