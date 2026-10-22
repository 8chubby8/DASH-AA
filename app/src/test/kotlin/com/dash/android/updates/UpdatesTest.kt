package com.dash.android.updates

import com.dash.android.system.linux.Command
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * System › Updates (DASH-AA 1.2.2): an update file made and sealed exactly as distro/build.sh makes it (openssl,
 * GNU tar), read and checked by DASH, and checked as dash-update checks it; and the settings copies.
 */
class UpdatesTest {
    private val dir = Files.createTempDirectory("dash-updates").toFile().apply { deleteOnExit() }

    private fun sh(vararg cmd: String) = Command.run(cmd.toList(), 30)!!.also { assertEquals(0, it.code, it.out) }

    /** A key, and a release sealed with it: release.txt, release.sig, a kernel and a system, in that order. */
    private fun release(name: String, text: String, key: File, tamper: Boolean = false): File {
        val r = File(dir, "r-$name").apply { mkdirs() }
        File(r, "release.txt").writeText(text)
        sh("openssl", "pkeyutl", "-sign", "-inkey", key.path, "-rawin", "-in", "$r/release.txt", "-out", "$r/release.sig")
        if (tamper) File(r, "release.txt").appendText("summary=added later\n")
        File(r, "dash_1.2.1.4_k.efi").writeText("kernel")
        File(r, "system.zst").writeBytes(ByteArray(3000) { it.toByte() })
        val out = File(dir, "$name.update")
        sh("tar", "--format=gnu", "-C", r.path, "-cf", out.path, "release.txt", "release.sig", "dash_1.2.1.4_k.efi", "system.zst")
        return out
    }

    private fun key(name: String): Pair<File, File> {
        val private = File(dir, "$name.pem"); val public = File(dir, "$name.pub")
        sh("openssl", "genpkey", "-algorithm", "ed25519", "-out", private.path)
        sh("openssl", "pkey", "-in", private.path, "-pubout", "-out", public.path)
        return private to public
    }

    private val text = """
        version=1.2.2-202610222310
        dash=1.2.2
        date=2026-10-22
        uuid=0b0e8a8e-0000-4000-8000-000000000000
        settings=same
        summary=Updates, from a USB stick
        summary=The boot menu says DASH
        kernel=dash_1.2.1.4_k.efi 00 generic
        system=system.zst 00 1000 2483412992
    """.trimIndent() + "\n"

    @Test fun `a release sealed by the build is read and its seal is good`() {
        val (private, public) = key("good")
        val check = UpdateFile.check(release("good", text, private), UpdateFile.key(public.readText()))
        assertIs<UpdateCheck.Sealed>(check)
        val r = check.release
        assertEquals("1.2.2-202610222310", r.version)
        assertEquals("1.2.2", r.dashVersion)
        assertEquals(listOf("Updates, from a USB stick", "The boot menu says DASH"), r.summary)
        assertEquals(2483412992L, r.systemBytes)
        assertEquals(false, r.settingsChanged)
    }

    @Test fun `a changed release, or one sealed with another key, is not sealed`() {
        val (private, public) = key("mine")
        val (other, _) = key("theirs")
        assertIs<UpdateCheck.NotSealed>(UpdateFile.check(release("changed", text, private, tamper = true), UpdateFile.key(public.readText())))
        assertIs<UpdateCheck.NotSealed>(UpdateFile.check(release("theirs", text, other), UpdateFile.key(public.readText())))
    }

    @Test fun `the system's own check agrees with DASH's`() {
        // As dash-update checks it: the two parts out of the tar, and openssl against the release key.
        val (private, public) = key("sys")
        val file = release("sys", text, private)
        val out = File(dir, "x").apply { mkdirs() }
        sh("sh", "-c", "tar -xOf '$file' --occurrence release.txt >'$out/t' && tar -xOf '$file' --occurrence release.sig >'$out/s'")
        sh("openssl", "pkeyutl", "-verify", "-pubin", "-inkey", public.path, "-rawin", "-in", "$out/t", "-sigfile", "$out/s")
    }

    @Test fun `a release is said as its three-number version and when it was built`() {
        assertEquals("DASH 1.2.2, built 22 Oct 23:10", UpdateFile.shown("1.2.2-202610222310"))
        assertEquals("DASH 1.2.1.3", UpdateFile.shown("1.2.1.3"))
    }

    @Test fun `not an update file`() {
        val junk = File(dir, "junk.update").apply { writeText("hello") }
        assertIs<UpdateCheck.Unreadable>(UpdateFile.check(junk, null))
    }

    @Test fun `settings are copied, the last three kept, and put back`() {
        val home = File(dir, "home"); val files = File(home, "files").apply { mkdirs() }
        File(files, "setting").writeText("one")
        val copies = SettingsCopies(files)
        val t = LocalDateTime.of(2026, 10, 22, 20, 0)
        copies.take("1.2.1.3", "1.2.1.4", t)
        File(files, "setting").writeText("two")
        repeat(3) { copies.take("1.2.1.${4 + it}", "1.2.1.${5 + it}", t.plusHours(1L + it)) }
        assertEquals(3, copies.list().size)
        assertNull(copies.takenFrom("1.2.1.3"), "the oldest went")
        val copy = copies.takenFrom("1.2.1.4")!!
        File(files, "setting").writeText("three")
        File(files, "new").writeText("x")
        copies.restore(copy)
        assertEquals("two", File(files, "setting").readText())
        assertTrue(!File(files, "new").exists())
        assertEquals(listOf("copies", "files"), home.list()!!.sorted())
    }
}
