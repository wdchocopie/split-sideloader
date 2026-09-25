package com.sideload.splitinstaller

import com.sideload.splitinstaller.core.axml.AxmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are real binary manifests produced by `aapt2 link`, not hand-built bytes, so
 * these tests fail if the parser drifts from what the platform toolchain actually emits.
 */
class AxmlParserTest {

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "missing test fixture $name"
        }.use { it.readBytes() }

    @Test
    fun `reads identity out of a base manifest`() {
        val manifest = AxmlParser.parse(fixture("manifest-base.axml"))

        assertEquals("com.example.fixture", manifest.packageName)
        assertEquals(4211L, manifest.versionCode)
        assertEquals("3.2.1-beta", manifest.versionName)
        assertEquals(24, manifest.minSdk)
        assertTrue("a manifest with no split attribute is the base", manifest.isBase)
        assertEquals(null, manifest.splitName)
    }

    @Test
    fun `reads the split name out of a config split`() {
        val manifest = AxmlParser.parse(fixture("manifest-split.axml"))

        assertEquals("com.example.fixture", manifest.packageName)
        assertEquals(
            "splits must carry the base versionCode or the installer rejects them",
            4211L,
            manifest.versionCode,
        )
        assertEquals("config.arm64_v8a", manifest.splitName)
        assertFalse(manifest.isBase)
        assertEquals(24, manifest.minSdk)
    }

    /**
     * What decides "this install is broken" for an app already on the device: a Unity game
     * cannot start without libmain.so, so its engine alone says native code is expected.
     */
    @Test
    fun `recognises a Unity game that requires its splits`() {
        val manifest = AxmlParser.parse(fixture("manifest-unity.axml"))

        assertEquals(com.sideload.splitinstaller.core.axml.NativeEngine.UNITY, manifest.engine)
        assertTrue(manifest.isSplitRequired)
        assertTrue(manifest.vendingSplitsRequired)
        assertTrue(manifest.declaresSplitsRequired)
        assertEquals(false, manifest.extractNativeLibs)
        assertEquals(20200L, manifest.versionCode)
        assertEquals(34, manifest.targetSdk)
    }

    @Test
    fun `a plain manifest declares no engine and no required splits`() {
        val manifest = AxmlParser.parse(fixture("manifest-base.axml"))

        assertEquals(null, manifest.engine)
        assertFalse(manifest.declaresSplitsRequired)
        assertEquals("extractNativeLibs left unset", null, manifest.extractNativeLibs)
    }

    @Test
    fun `rejects something that is not binary xml`() {
        val error = runCatching { AxmlParser.parse("<manifest/>".toByteArray()) }.exceptionOrNull()
        assertNotNull("plain text XML is not a compiled manifest", error)
    }

    @Test
    fun `rejects an empty buffer`() {
        val error = runCatching { AxmlParser.parse(ByteArray(0)) }.exceptionOrNull()
        assertNotNull(error)
    }
}
