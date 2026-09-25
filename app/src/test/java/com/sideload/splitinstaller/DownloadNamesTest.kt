package com.sideload.splitinstaller

import com.sideload.splitinstaller.core.sources.DownloadNames
import com.sideload.splitinstaller.core.sources.Source
import com.sideload.splitinstaller.core.sources.SourceTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNamesTest {

    @Test
    fun `takes the quoted filename from content-disposition`() {
        assertEquals(
            "com.HoYoverse.Nap_2.2.0-20200_4arch_7dpi_apkmirror.com.apkm",
            DownloadNames.fileName(
                "https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=1&key=abc",
                "attachment; filename=\"com.HoYoverse.Nap_2.2.0-20200_4arch_7dpi_apkmirror.com.apkm\"",
                "application/octet-stream",
            ),
        )
    }

    @Test
    fun `prefers the encoded filename star form`() {
        assertEquals(
            "Zenless Zone Zero_2.2.0_APKPure.xapk",
            DownloadNames.fileName(
                "https://d.apkpure.com/b/XAPK/com.HoYoverse.Nap?version=latest",
                "attachment; filename=\"fallback.xapk\"; filename*=UTF-8''Zenless%20Zone%20Zero_2.2.0_APKPure.xapk",
                "application/xapk-package-archive",
            ),
        )
    }

    @Test
    fun `accepts an unquoted filename`() {
        assertEquals(
            "termux.apk",
            DownloadNames.fileName("https://x.example/dl", "attachment; filename=termux.apk; size=1234", null),
        )
    }

    @Test
    fun `falls back to the last path segment`() {
        assertEquals(
            "com.termux_1020.apk",
            DownloadNames.fileName("https://f-droid.org/repo/com.termux_1020.apk?x=1", null, null),
        )
    }

    @Test
    fun `adds an apk extension when the server only says it is an apk`() {
        assertEquals("com.foo.apk", DownloadNames.fileName("https://x.example/b/APK/com.foo", null, "application/vnd.android.package-archive"))
    }

    /** The name becomes a path under Download/; it must not be able to leave it. */
    @Test
    fun `cannot climb out of the download folder`() {
        val name = DownloadNames.fileName("https://x.example/a", "attachment; filename=\"../../data/evil.apk\"", null)
        assertEquals("evil.apk", name)
        assertFalse(name.contains('/'))
        assertEquals("evil.apk", DownloadNames.sanitize("..\\..\\evil.apk"))
        assertEquals("download.bin", DownloadNames.fileName("https://x.example/", "attachment; filename=\"..\"", null))
    }

    @Test
    fun `replaces characters no filesystem accepts`() {
        assertEquals("a_b_c_.apks", DownloadNames.sanitize("a:b*c?.apks"))
    }

    @Test
    fun `keeps the extension when shortening a long name`() {
        val long = "x".repeat(400) + ".xapk"
        val name = DownloadNames.sanitize(long)
        assertTrue(name.length <= 150)
        assertTrue(name.endsWith(".xapk"))
    }

    @Test
    fun `builds search urls with the query encoded`() {
        val source = Source(
            id = "t", name = "Test", homeUrl = "https://www.example.com/",
            searchUrl = "https://www.example.com/?s={q}&type=apk",
            formats = emptyList(), trust = SourceTrust.CUSTOM, builtin = false,
        )
        assertEquals("https://www.example.com/?s=zenless+zone+zero&type=apk", source.searchFor("  zenless zone zero "))
        assertEquals("https://www.example.com/?s=a%26b&type=apk", source.searchFor("a&b"))
        assertEquals("an empty query opens the home page", "https://www.example.com/", source.searchFor(" "))
        assertEquals("example.com", source.host)
        assertTrue(source.owns("https://cdn.example.com/file.apk"))
        assertFalse(source.owns("https://example.com.evil.net/"))
    }
}
