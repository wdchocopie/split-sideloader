package com.sideload.splitinstaller

import com.sideload.splitinstaller.core.sign.ApkSignatures
import com.sideload.splitinstaller.core.sign.SignatureScheme
import com.sideload.splitinstaller.core.zip.ChannelSource
import com.sideload.splitinstaller.core.zip.ZipReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Fixtures were signed with `apksigner` from build-tools 35 using two throwaway keys, and
 * the expected fingerprints are the ones `apksigner verify --print-certs` reported. If the
 * signing-block parser drifts from what the platform tooling writes, these fail.
 */
class ApkSignaturesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val oldKey = "17ca21f1bbf89f40f3584fe9f9feaaede36c087e0065f7318dd85b6eb52fa5ca"
    private val newKey = "11644c52d5399db2ab40fbc969dc3eef39898b4c7f073ca39c6602fdc73ebc15"

    private fun fixture(name: String): File {
        val url = requireNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }
        return File(url.toURI())
    }

    private fun read(name: String) = ZipReader.open(fixture(name)).use { ApkSignatures.read(it) }

    @Test
    fun `reads the signer from a v2 and v3 signing block`() {
        val sig = requireNotNull(read("v123.signed.apk"))
        assertTrue(SignatureScheme.V2 in sig.schemes)
        assertTrue(SignatureScheme.V3 in sig.schemes)
        assertEquals(oldKey, sig.signer?.sha256)
        assertTrue(sig.signer!!.subject.contains("CN=Split Test Old"))
        assertEquals("Split Test Old", sig.signer!!.commonName)
    }

    @Test
    fun `falls back to the JAR signature when there is no signing block`() {
        val sig = requireNotNull(read("v1.signed.apk"))
        assertEquals(setOf(SignatureScheme.V1), sig.schemes)
        assertEquals(oldKey, sig.signer?.sha256)
        assertEquals("META-INF/OLD.RSA", sig.v1SignerFile)
    }

    /**
     * A key-rotated APK: v3 still carries the old key for older platforms, v3.1 the new one
     * with a proof-of-rotation. An installed copy signed by either key must match, or the
     * app would warn about a legitimate update.
     */
    @Test
    fun `follows key rotation so a re-keyed update still matches`() {
        val sig = requireNotNull(read("rotated.signed.apk"))
        assertTrue(SignatureScheme.V31 in sig.schemes)
        assertEquals("the newest signer wins", newKey, sig.signer?.sha256)
        assertTrue("old key must be accepted through the lineage", oldKey in sig.allSha256)
        assertTrue(newKey in sig.allSha256)
    }

    @Test
    fun `an unsigned APK has no signature`() {
        assertNull(read("unsigned.apk"))
    }

    /** The path the inspector actually uses: the APK read in place inside a bundle. */
    @Test
    fun `reads the signing block of an APK nested inside a bundle`() {
        val apk = fixture("v123.signed.apk").readBytes()
        val bundle = temp.newFile("app.xapk")
        ZipOutputStream(bundle.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write("{}".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(
                ZipEntry("com.example.fixture.apk").apply {
                    method = ZipEntry.STORED
                    size = apk.size.toLong()
                    compressedSize = apk.size.toLong()
                    crc = CRC32().apply { update(apk) }.value
                }
            )
            zos.write(apk)
            zos.closeEntry()
        }

        ZipReader(ChannelSource(FileInputStream(bundle).channel)).use { outer ->
            val nested = requireNotNull(outer.nested(requireNotNull(outer["com.example.fixture.apk"])))
            val sig = nested.use { ApkSignatures.read(it) }
            assertNotNull(sig)
            assertEquals(oldKey, sig!!.signer?.sha256)
        }
    }

    @Test
    fun `fingerprint formats like keytool`() {
        val cert = requireNotNull(read("v123.signed.apk")?.signer)
        assertEquals("17:CA:21:F1:BB:F8:9F:40", cert.formatted(8))
        assertEquals(32 * 3 - 1, cert.formatted().length)
    }
}
