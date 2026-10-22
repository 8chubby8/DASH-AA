package com.dash.android.updates

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * **Settings copied before each update** (DASH-AA 1.2.2). Just before a release is installed, DASH copies its
 * own settings (the `files` folder: settings, modules, sound, display) — not Wi-Fi, not Bluetooth pairings,
 * which a roll back should never forget (Roger, 2026-10-22). On going back to the release a copy was taken
 * from, DASH asks: keep the settings as they are now, or put that copy back. The last three are kept.
 *
 * Each copy is a folder in `copies/`, beside `files`: the copy itself and `about`, which says which release
 * it was taken from and which was being installed.
 *
 * **For native:** take as it is if native gets updates of its own; the folder is the same.
 */
class SettingsCopies(private val files: File, private val keep: Int = 3) {
    data class Copy(val folder: File, val from: String, val to: String, val taken: LocalDateTime)

    private val home = File(files.parentFile, "copies")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun list(): List<Copy> = home.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
        val about = runCatching { File(dir, "about").readLines() }.getOrNull() ?: return@mapNotNull null
        fun get(k: String) = about.firstOrNull { it.startsWith("$k=") }?.substringAfter('=')
        val taken = runCatching { LocalDateTime.parse(get("taken")) }.getOrNull() ?: return@mapNotNull null
        Copy(dir, get("from") ?: return@mapNotNull null, get("to").orEmpty(), taken)
    }.sortedByDescending { it.taken }

    /** The newest copy taken while [version] was running — the one to offer on going back to it. */
    fun takenFrom(version: String): Copy? = list().firstOrNull { it.from == version }

    /** Copy the settings now, before [to] is installed over [from]; older copies past the last [keep] go. */
    fun take(from: String, to: String, now: LocalDateTime = LocalDateTime.now()): Copy {
        home.mkdirs()
        val dir = File(home, "${now.format(stamp)}_$from")
        val part = File(home, "${dir.name}.part")
        part.deleteRecursively()
        if (files.isDirectory) files.copyRecursively(File(part, "files"), overwrite = true)
        File(part, "about").writeText("from=$from\nto=$to\ntaken=$now\n")
        dir.deleteRecursively()
        check(part.renameTo(dir)) { "the copy could not be put in place" }
        list().drop(keep).forEach { it.folder.deleteRecursively() }
        return Copy(dir, from, to, now)
    }

    /** Put [copy] back in place of the settings now. The machine is restarted straight after. */
    fun restore(copy: Copy) {
        val back = File(files.parentFile, "files.restoring")
        val old = File(files.parentFile, "files.replaced")
        back.deleteRecursively(); old.deleteRecursively()
        File(copy.folder, "files").let { if (it.isDirectory) it.copyRecursively(back, overwrite = true) else back.mkdirs() }
        if (files.exists()) check(files.renameTo(old)) { "the settings could not be moved aside" }
        if (!back.renameTo(files)) {
            old.renameTo(files)
            error("the copy could not be put in place")
        }
        old.deleteRecursively()
    }
}
