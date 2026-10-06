package com.sideload.splitinstaller

import com.sideload.splitinstaller.core.install.AutoInstall
import com.sideload.splitinstaller.core.install.AutoInstallBlock
import com.sideload.splitinstaller.core.sources.DownloadNames
import com.sideload.splitinstaller.core.sources.LinkProbe
import com.sideload.splitinstaller.core.sources.Links
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Links pasted or shared into the app, and the rule for installs nobody taps Install for. */
class LinksTest {

    @Test
    fun `the link is found inside what a chat app or browser shares`() {
        assertEquals(
            "https://github.com/owner/repo/releases/download/v1/app.apk",
            Links.extractUrl("New build! https://github.com/owner/repo/releases/download/v1/app.apk."),
        )
        assertEquals("https://example.org/a.apk", Links.extractUrl("(see https://example.org/a.apk)"))
        assertEquals("https://example.org/a.apk?x=1&y=2", Links.extractUrl("Title\nhttps://example.org/a.apk?x=1&y=2\n"))
    }

    @Test
    fun `a shouted scheme is lowered and full-width punctuation ends the link`() {
        assertEquals("https://example.org/A.apk", Links.extractUrl("HTTPS://example.org/A.apk"))
        assertEquals("https://example.org/a.apk", Links.extractUrl("tải ở https://example.org/a.apk，cảm ơn"))
    }

    @Test
    fun `a link to one file is told from a project page`() {
        assertTrue(Links.isFileLink("https://github.com/o/r/releases/download/v1/app.apk"))
        assertTrue(Links.isFileLink("https://gitlab.com/g/p/-/package_files/1/download"))
        assertTrue(Links.isFileLink("https://example.org/files/app.xapk?token=1"))
        assertFalse(Links.isFileLink("https://github.com/o/r"))
        assertFalse(Links.isFileLink("https://f-droid.org/packages/org.fdroid.fdroid/"))
    }

    @Test
    fun `text without a web link gives nothing`() {
        assertNull(Links.extractUrl("no link here"))
        assertNull(Links.extractUrl("ftp://example.org/a.apk"))
        assertNull(Links.extractUrl(null))
    }

    @Test
    fun `only https counts as safe to fetch`() {
        assertTrue(Links.isHttps("https://example.org/a.apk"))
        assertFalse(Links.isHttps("http://example.org/a.apk"))
    }

    @Test
    fun `a zip is told from a web page by its first bytes`() {
        val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 20, 0)
        assertTrue(Links.looksLikeZip(zip))
        assertFalse(Links.looksLikeHtml("application/octet-stream", zip))
        val page = "  <!DOCTYPE html><html>".toByteArray()
        assertFalse(Links.looksLikeZip(page))
        assertTrue(Links.looksLikeHtml(null, page))
        assertTrue(Links.looksLikeHtml("text/html; charset=utf-8", ByteArray(0)))
    }

    @Test
    fun `a nameless zip gets the extension its first entry calls for`() {
        assertEquals(".apks", Links.extensionFor(zipStartingWith("base.apk")))
        assertEquals(".apks", Links.extensionFor(zipStartingWith("manifest.json")))
        assertEquals(".apk", Links.extensionFor(zipStartingWith("AndroidManifest.xml")))
        assertEquals(".apk", Links.extensionFor("not a zip".toByteArray()))
    }

    @Test
    fun `the whole size comes from the range answer or a plain length`() {
        assertEquals(52_428_800L, LinkProbe.totalSize("bytes 0-2047/52428800", 2048, 206))
        assertEquals(1000L, LinkProbe.totalSize(null, 1000, 200))
        assertNull(LinkProbe.totalSize(null, 2048, 206))
        assertNull(LinkProbe.totalSize("bytes 0-2047/*", -1, 206))
    }

    @Test
    fun `a source's file name travels as a content disposition`() {
        assertEquals("attachment; filename=\"App-8.1.apk\"", DownloadNames.attachment("App-8.1.apk"))
        assertEquals("App-8.1.apk", DownloadNames.fromContentDisposition(DownloadNames.attachment("App-8.1.apk")))
        assertNull(DownloadNames.attachment(null))
        // A name cannot climb out of the download folder.
        assertFalse(DownloadNames.attachment("../../evil.apk")!!.contains("/"))
    }

    @Test
    fun `an install nobody taps for goes ahead only when nothing is off`() {
        fun block(
            pkg: String? = "com.example",
            expected: String? = null,
            error: Boolean = false,
            problems: Boolean = false,
            mismatch: Boolean = false,
            installed: Long? = null,
            code: Long = 10,
        ) = AutoInstall.blockedBy(pkg, expected, "com.sideload.splitinstaller", error, problems, mismatch, installed, code)

        assertNull(block())
        assertNull(block(installed = 9))
        assertEquals(AutoInstallBlock.BUNDLE, block(error = true))
        // Nothing to check a nameless package against, so it is not installed by itself.
        assertEquals(AutoInstallBlock.BUNDLE, block(pkg = null))
        assertEquals(AutoInstallBlock.OTHER_PACKAGE, block(expected = "com.other"))
        assertEquals(AutoInstallBlock.OWN_PACKAGE, block(pkg = "com.sideload.splitinstaller"))
        assertEquals(AutoInstallBlock.SELECTION, block(problems = true))
        assertEquals(AutoInstallBlock.SIGNER, block(mismatch = true))
        assertEquals(AutoInstallBlock.DOWNGRADE, block(installed = 11))
    }

    /** The start of a zip whose first local file header names [entry]. */
    private fun zipStartingWith(entry: String): ByteArray {
        val name = entry.toByteArray()
        val header = ByteArray(30)
        header[0] = 'P'.code.toByte(); header[1] = 'K'.code.toByte(); header[2] = 3; header[3] = 4
        header[26] = (name.size and 0xff).toByte()
        header[27] = (name.size shr 8).toByte()
        return header + name
    }
}
