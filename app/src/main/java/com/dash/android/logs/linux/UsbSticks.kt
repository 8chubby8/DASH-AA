package com.dash.android.logs.linux

import com.dash.android.system.linux.Command
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * **USB sticks** (1.1.9) — where Developer › Logs saves to, with no file picker and no desktop.
 *
 * The sticks are found with `lsblk`; one not yet opened is mounted with `udisksctl`, which the person at
 * the seat may do with no password (udisks' own rule for removable drives — nothing of DASH's), and
 * unmounted again when the save is done, so it can be pulled out. A stick the desktop opened is left
 * open. Without udisks only sticks already open are offered.
 */
object UsbSticks {
    private const val TAG = "DashLogs"

    data class Stick(val device: String, val name: String, val size: String, val mountpoint: String?)

    private val WRITABLE = setOf("vfat", "exfat", "ntfs", "ntfs3", "ext4", "ext3", "ext2", "btrfs", "f2fs", "xfs")

    fun canMount(): Boolean = listOf("/usr/bin/udisksctl", "/bin/udisksctl").any { File(it).canExecute() }

    fun list(): List<Stick> = runCatching {
        val out = run("lsblk", "-J", "--list", "-o", "PATH,TYPE,TRAN,RM,HOTPLUG,FSTYPE,LABEL,SIZE,MOUNTPOINT,VENDOR,MODEL") ?: return emptyList()
        val devices = Json.parseToJsonElement(out).jsonObject["blockdevices"]?.jsonArray.orEmpty().map { it.jsonObject }
        // The disk's own maker and model, to name its partitions by.
        val disks = devices.filter { it.s("type") == "disk" }
        devices.filter { d ->
            val usb = d.s("tran") == "usb" || d.b("hotplug") == true || d.b("rm") == true
            usb && d.s("type") in setOf("part", "disk") && d.s("fstype") in WRITABLE &&
                // Never part of the system itself, even on a machine that boots from USB.
                d.s("mountpoint").let { m -> m == null || listOf("/run/media/", "/media/", "/mnt/").any { m.startsWith(it) } }
        }.map { d ->
            val path = d.s("path")!!
            val disk = disks.firstOrNull { path.startsWith(it.s("path") ?: "\u0000") }
            val made = listOfNotNull(disk?.s("vendor")?.trim(), disk?.s("model")?.trim()).joinToString(" ").ifBlank { null }
            Stick(path, d.s("label")?.takeIf { it.isNotBlank() } ?: made ?: path.substringAfterLast('/'), d.s("size") ?: "", d.s("mountpoint"))
        }
    }.onFailure { Log.w(TAG, "could not list USB sticks: ${it.message}") }.getOrDefault(emptyList())

    /** Open [stick] if it is not open; the folder it is open at, or null with why not. */
    fun mount(stick: Stick): Pair<File?, String> {
        stick.mountpoint?.let { return File(it) to "" }
        if (!canMount()) return null to "This stick is not open, and udisks (which opens sticks) is not installed."
        val said = run("udisksctl", "mount", "-b", stick.device, "--no-user-interaction")
            ?: return null to "The stick could not be opened."
        val at = Regex("""at (/\S+)""").find(said)?.groupValues?.get(1)?.trimEnd('.')
            ?: return null to "The stick could not be opened: ${said.trim()}"
        return File(at) to ""
    }

    fun unmount(stick: Stick) {
        run("udisksctl", "unmount", "-b", stick.device, "--no-user-interaction")
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

    private fun run(vararg cmd: String): String? = runCatching {
        val r = Command.run(cmd.toList(), 30) ?: return null
        if (r.code != 0) { Log.w(TAG, "${cmd.first()} ${cmd.getOrNull(1)}: ${r.out.trim()}"); return null }
        r.out
    }.getOrNull()
}
