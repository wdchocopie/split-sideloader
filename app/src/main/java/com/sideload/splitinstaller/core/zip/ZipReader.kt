package com.sideload.splitinstaller.core.zip

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Random-access byte source. Positional reads only, so one source can back several
 * concurrent streams without them fighting over a shared cursor.
 */
interface DataSource : Closeable {
    val size: Long
    fun readAt(position: Long, dst: ByteArray, off: Int, len: Int): Int
}

class ChannelSource(
    private val channel: FileChannel,
    private val extra: Closeable? = null,
) : DataSource {

    override val size: Long = channel.size()

    override fun readAt(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (position >= size) return -1
        val buf = ByteBuffer.wrap(dst, off, len)
        return channel.read(buf, position)
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { extra?.close() }
    }
}

/** A window onto another source. Lets a nested, uncompressed APK be read as a zip of its own. */
class SliceSource(
    private val base: DataSource,
    private val offset: Long,
    override val size: Long,
) : DataSource {

    override fun readAt(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (position >= size) return -1
        val capped = minOf(len.toLong(), size - position).toInt()
        if (capped <= 0) return -1
        return base.readAt(offset + position, dst, off, capped)
    }

    /** Slices never own the underlying source. */
    override fun close() = Unit
}

class ZipEntryInfo(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val crc: Long,
    val localHeaderOffset: Long,
    val flags: Int,
) {
    val isStored: Boolean get() = method == 0
    val isDirectory: Boolean get() = name.endsWith("/")
    val isEncrypted: Boolean get() = (flags and 1) != 0
}

private const val EOCD_SIG = 0x06054b50
private const val ZIP64_LOCATOR_SIG = 0x07064b50
private const val ZIP64_EOCD_SIG = 0x06064b50
private const val CEN_SIG = 0x02014b50
private const val LOC_SIG = 0x04034b50

private const val U16 = 0xFFFF
private const val U32 = 0xFFFFFFFFL

/**
 * Minimal, dependency-free zip reader built for one job: pulling APKs out of a split
 * bundle without ever unpacking it to disk.
 *
 * Handles ZIP64, because bundles above 4 GB are ordinary for game asset packs, and can
 * hand back a [DataSource] window over a STORED entry. That window is what makes it
 * possible to read a nested APK's manifest and lib layout without inflating 300 MB first.
 */
class ZipReader(private val src: DataSource, private val ownsSource: Boolean = true) : Closeable {

    val entries: List<ZipEntryInfo>
    private val byName: Map<String, ZipEntryInfo>
    private val dataOffsets = HashMap<String, Long>()

    /** Where the central directory starts. The APK Signing Block ends exactly here. */
    val centralDirectoryOffset: Long

    /** The underlying bytes, for readers that need to look outside the entries themselves. */
    val source: DataSource get() = src

    init {
        val cd = locateCentralDirectory()
        centralDirectoryOffset = cd.offset
        entries = readCentralDirectory(cd.offset, cd.size, cd.count)
        byName = entries.associateBy { it.name }
    }

    operator fun get(name: String): ZipEntryInfo? = byName[name]

    fun find(predicate: (ZipEntryInfo) -> Boolean): ZipEntryInfo? = entries.firstOrNull(predicate)

    /** Absolute offset of an entry's payload, resolved from the local header. */
    fun dataOffset(entry: ZipEntryInfo): Long = dataOffsets.getOrPut(entry.name) {
        val head = ByteArray(30)
        readFully(src, entry.localHeaderOffset, head, 0, 30)
        val b = le(head)
        if (b.getInt(0) != LOC_SIG) throw IOException("bad local header for " + entry.name)
        val nameLen = b.getShort(26).toInt() and U16
        val extraLen = b.getShort(28).toInt() and U16
        entry.localHeaderOffset + 30L + nameLen + extraLen
    }

    /** Raw, still-compressed bytes of an entry. */
    fun openRaw(entry: ZipEntryInfo): InputStream =
        SourceInputStream(src, dataOffset(entry), entry.compressedSize)

    /** Decompressed bytes of an entry. */
    fun open(entry: ZipEntryInfo): InputStream {
        if (entry.isEncrypted) throw IOException("encrypted entry not supported: " + entry.name)
        val raw = openRaw(entry)
        return when (entry.method) {
            0 -> raw
            8 -> InflaterInputStream(raw, Inflater(true), DEFAULT_BUFFER_SIZE)
            else -> throw IOException("unsupported compression method " + entry.method + " for " + entry.name)
        }
    }

    fun readAll(entry: ZipEntryInfo, limit: Long = 64L * 1024 * 1024): ByteArray {
        if (entry.size > limit) throw IOException(entry.name + " is " + entry.size + " bytes, over the limit")
        return open(entry).use { it.readBytes() }
    }

    /**
     * A source view over an entry stored uncompressed, or null when the entry is deflated.
     * Bundles almost always store their APKs uncompressed, because re-compressing an
     * already-compressed APK buys nothing.
     */
    fun sliceOf(entry: ZipEntryInfo): DataSource? {
        if (!entry.isStored || entry.isEncrypted) return null
        return SliceSource(src, dataOffset(entry), entry.compressedSize)
    }

    /** Open a nested zip, an APK inside the bundle, in place. Null when not possible. */
    fun nested(entry: ZipEntryInfo): ZipReader? {
        val slice = sliceOf(entry) ?: return null
        return runCatching { ZipReader(slice, ownsSource = false) }.getOrNull()
    }

    override fun close() {
        if (ownsSource) src.close()
    }

    // ---- central directory -------------------------------------------------

    private class Cd(val offset: Long, val size: Long, val count: Long)

    private fun locateCentralDirectory(): Cd {
        val fileSize = src.size
        if (fileSize < 22) throw IOException("file too small to be a zip (" + fileSize + " bytes)")

        // The EOCD sits at the very end, unless a trailing archive comment pushes it back.
        val scanLen = minOf(fileSize, 22L + U16).toInt()
        val tail = ByteArray(scanLen)
        readFully(src, fileSize - scanLen, tail, 0, scanLen)
        val tb = le(tail)

        var eocd = -1
        for (i in scanLen - 22 downTo 0) {
            if (tb.getInt(i) == EOCD_SIG) { eocd = i; break }
        }
        if (eocd < 0) throw IOException("not a zip archive: no end-of-central-directory record")

        var count = (tb.getShort(eocd + 10).toInt() and U16).toLong()
        var cdSize = tb.getInt(eocd + 12).toLong() and U32
        var cdOffset = tb.getInt(eocd + 16).toLong() and U32

        if (count == U16.toLong() || cdSize == U32 || cdOffset == U32) {
            val locAt = eocd - 20
            if (locAt >= 0 && tb.getInt(locAt) == ZIP64_LOCATOR_SIG) {
                val z64At = tb.getLong(locAt + 8)
                val z64 = ByteArray(56)
                readFully(src, z64At, z64, 0, 56)
                val zb = le(z64)
                if (zb.getInt(0) != ZIP64_EOCD_SIG) throw IOException("bad ZIP64 end-of-central-directory record")
                count = zb.getLong(32)
                cdSize = zb.getLong(40)
                cdOffset = zb.getLong(48)
            }
        }
        if (cdOffset < 0 || cdOffset + cdSize > fileSize) {
            throw IOException("central directory out of bounds: the file is truncated or still downloading")
        }
        return Cd(cdOffset, cdSize, count)
    }

    private fun readCentralDirectory(offset: Long, size: Long, count: Long): List<ZipEntryInfo> {
        if (size > 128L * 1024 * 1024) throw IOException("central directory unreasonably large")
        val cd = ByteArray(size.toInt())
        readFully(src, offset, cd, 0, cd.size)
        val b = le(cd)

        val out = ArrayList<ZipEntryInfo>(count.coerceIn(0, 100_000).toInt())
        var p = 0
        while (p + 46 <= cd.size) {
            if (b.getInt(p) != CEN_SIG) break
            val flags = b.getShort(p + 8).toInt() and U16
            val method = b.getShort(p + 10).toInt() and U16
            val crc = b.getInt(p + 16).toLong() and U32
            var compressed = b.getInt(p + 20).toLong() and U32
            var uncompressed = b.getInt(p + 24).toLong() and U32
            val nameLen = b.getShort(p + 28).toInt() and U16
            val extraLen = b.getShort(p + 30).toInt() and U16
            val commentLen = b.getShort(p + 32).toInt() and U16
            var lho = b.getInt(p + 42).toLong() and U32

            val nameAt = p + 46
            if (nameAt + nameLen > cd.size) break
            val name = String(cd, nameAt, nameLen, Charsets.UTF_8)

            // ZIP64 extended info overrides whatever was clamped to 0xFFFFFFFF above.
            if (uncompressed == U32 || compressed == U32 || lho == U32) {
                var e = nameAt + nameLen
                val extraEnd = e + extraLen
                while (e + 4 <= extraEnd && e + 4 <= cd.size) {
                    val id = b.getShort(e).toInt() and U16
                    val len = b.getShort(e + 2).toInt() and U16
                    var f = e + 4
                    if (id == 0x0001) {
                        if (uncompressed == U32 && f + 8 <= cd.size) { uncompressed = b.getLong(f); f += 8 }
                        if (compressed == U32 && f + 8 <= cd.size) { compressed = b.getLong(f); f += 8 }
                        if (lho == U32 && f + 8 <= cd.size) { lho = b.getLong(f) }
                        break
                    }
                    e += 4 + len
                }
            }

            out.add(ZipEntryInfo(name, method, compressed, uncompressed, crc, lho, flags))
            p = nameAt + nameLen + extraLen + commentLen
        }
        if (out.isEmpty()) throw IOException("central directory holds no entries")
        return out
    }

    companion object {
        /** Open a zip that lives in a plain file, such as an installed APK. */
        fun open(file: java.io.File): ZipReader {
            val channel = java.io.FileInputStream(file).channel
            return try {
                ZipReader(ChannelSource(channel))
            } catch (t: Throwable) {
                runCatching { channel.close() }
                throw t
            }
        }

        internal fun le(bytes: ByteArray): ByteBuffer =
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        internal fun readFully(src: DataSource, position: Long, dst: ByteArray, off: Int, len: Int) {
            var got = 0
            while (got < len) {
                val n = src.readAt(position + got, dst, off + got, len - got)
                if (n <= 0) throw EOFException("short read at " + (position + got))
                got += n
            }
        }
    }
}

/** Fixed-length stream over a window of a [DataSource]. */
private class SourceInputStream(
    private val src: DataSource,
    private val start: Long,
    private val length: Long,
) : InputStream() {

    private var pos = 0L
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val want = minOf(len.toLong(), length - pos).toInt()
        if (want <= 0) return -1
        val n = src.readAt(start + pos, b, off, want)
        if (n <= 0) return -1
        pos += n
        return n
    }

    override fun skip(n: Long): Long {
        val step = minOf(n, length - pos).coerceAtLeast(0)
        pos += step
        return step
    }

    override fun available(): Int = (length - pos).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}
