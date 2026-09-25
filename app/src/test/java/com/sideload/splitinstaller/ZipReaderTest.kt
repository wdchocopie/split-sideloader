package com.sideload.splitinstaller

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
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun open(file: File) = ZipReader(ChannelSource(FileInputStream(file).channel))

    private fun writeZip(
        file: File,
        entries: List<Triple<String, ByteArray, Int>>,
    ): File {
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (name, bytes, method) ->
                val entry = ZipEntry(name).apply {
                    setMethod(method)
                    if (method == ZipEntry.STORED) {
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    }
                }
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `reads stored and deflated entries back byte for byte`() {
        val stored = "stored payload, not compressed".repeat(40).toByteArray()
        val deflated = "deflate me ".repeat(5000).toByteArray()
        val file = writeZip(
            temp.newFile("mixed.zip"),
            listOf(
                Triple("a/stored.bin", stored, ZipEntry.STORED),
                Triple("b/deflated.bin", deflated, ZipEntry.DEFLATED),
            ),
        )

        open(file).use { zip ->
            assertEquals(listOf("a/stored.bin", "b/deflated.bin"), zip.entries.map { it.name })

            val s = requireNotNull(zip["a/stored.bin"])
            assertTrue("stored entry should report method 0", s.isStored)
            assertEquals(stored.size.toLong(), s.size)
            assertTrue(zip.open(s).use { it.readBytes() }.contentEquals(stored))

            val d = requireNotNull(zip["b/deflated.bin"])
            assertTrue("deflated entry should not be stored", !d.isStored)
            assertEquals(deflated.size.toLong(), d.size)
            assertTrue(zip.open(d).use { it.readBytes() }.contentEquals(deflated))
        }
    }

    @Test
    fun `central directory crc matches what we read back`() {
        val payload = "checksum me".repeat(1000).toByteArray()
        val file = writeZip(temp.newFile("crc.zip"), listOf(Triple("p.bin", payload, ZipEntry.DEFLATED)))

        open(file).use { zip ->
            val entry = requireNotNull(zip["p.bin"])
            val actual = CRC32().apply { update(zip.open(entry).use { it.readBytes() }) }.value
            assertEquals(
                "the CRC we verify installs against must come from the archive itself",
                entry.crc,
                actual,
            )
        }
    }

    /**
     * The path that lets a split bundle be inspected without unpacking: an APK stored
     * uncompressed inside the bundle is opened in place, as its own archive.
     */
    @Test
    fun `opens a nested archive stored uncompressed`() {
        val innerBytes = writeZip(
            temp.newFile("inner.zip"),
            listOf(
                Triple("AndroidManifest.xml", "not really xml".toByteArray(), ZipEntry.DEFLATED),
                Triple("lib/arm64-v8a/libmain.so", ByteArray(2048) { 7 }, ZipEntry.STORED),
            ),
        ).readBytes()

        val outer = writeZip(
            temp.newFile("outer.xapk"),
            listOf(
                Triple("manifest.json", "{}".toByteArray(), ZipEntry.DEFLATED),
                Triple("config.arm64_v8a.apk", innerBytes, ZipEntry.STORED),
            ),
        )

        open(outer).use { zip ->
            val apkEntry = requireNotNull(zip["config.arm64_v8a.apk"])
            val nested = requireNotNull(zip.nested(apkEntry)) { "a STORED nested zip must open" }
            assertEquals(
                listOf("AndroidManifest.xml", "lib/arm64-v8a/libmain.so"),
                nested.entries.map { it.name },
            )
            val so = requireNotNull(nested["lib/arm64-v8a/libmain.so"])
            assertEquals(2048L, so.size)
            assertEquals(2048, nested.open(so).use { it.readBytes() }.size)
        }
    }

    @Test
    fun `a deflated nested archive cannot be opened in place`() {
        val innerBytes = writeZip(
            temp.newFile("inner2.zip"),
            listOf(Triple("x", ByteArray(64), ZipEntry.STORED)),
        ).readBytes()
        val outer = writeZip(
            temp.newFile("outer2.xapk"),
            listOf(Triple("base.apk", innerBytes, ZipEntry.DEFLATED)),
        )

        open(outer).use { zip ->
            val entry = requireNotNull(zip["base.apk"])
            assertNull("deflated entries have no in-place window", zip.sliceOf(entry))
        }
    }

    /**
     * A part-downloaded bundle must be refused outright. Reading one as if it were whole is
     * how a truncated APK reaches the package manager.
     */
    @Test
    fun `refuses a truncated archive`() {
        val file = writeZip(
            temp.newFile("truncated.zip"),
            listOf(Triple("big.bin", ByteArray(40_000) { it.toByte() }, ZipEntry.DEFLATED)),
        )
        RandomAccessFile(file, "rw").use { it.setLength(file.length() - 40) }

        val error = runCatching { open(file).use { it.entries } }.exceptionOrNull()
        assertNotNull("a truncated zip must not parse", error)
        assertTrue("should surface as an IO problem", error is IOException)
    }

    @Test
    fun `survives an archive comment after the end record`() {
        val file = temp.newFile("commented.zip")
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.setComment("x".repeat(2000))
            zos.putNextEntry(ZipEntry("only.bin"))
            zos.write(ByteArray(128) { 3 })
            zos.closeEntry()
        }
        open(file).use { zip ->
            assertEquals(listOf("only.bin"), zip.entries.map { it.name })
        }
    }
}
