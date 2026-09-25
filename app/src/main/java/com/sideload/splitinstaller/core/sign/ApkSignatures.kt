package com.sideload.splitinstaller.core.sign

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.sideload.splitinstaller.core.zip.ZipReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

data class CertInfo(
    /** Lowercase hex SHA-256 of the DER certificate — the same value `apksigner` prints. */
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
) {
    /**
     * Play App Signing issues certificates with a generic Google subject. For a Play-signed
     * app that is the *expected* result; a publisher-named subject would be the odd one.
     */
    val isPlayAppSigning: Boolean
        get() = subject.contains("CN=Android") && subject.contains("O=Google Inc.")

    val commonName: String
        get() = subject.split(',').map { it.trim() }
            .firstOrNull { it.startsWith("CN=") }?.removePrefix("CN=") ?: subject

    /** `AB:CD:EF…` grouping, as keytool and apksigner show it. */
    fun formatted(bytes: Int = 32): String =
        sha256.take(bytes * 2).uppercase().chunked(2).joinToString(":")
}

enum class SignatureScheme(val label: String) { V1("v1"), V2("v2"), V3("v3"), V31("v3.1") }

data class ApkSignature(
    val schemes: Set<SignatureScheme>,
    /** The signer the platform will actually compare: v3.1, then v3, then v2, then v1. */
    val signer: CertInfo?,
    /** Earlier keys this APK proves it rotated away from (v3 proof-of-rotation). */
    val lineage: List<CertInfo> = emptyList(),
    /** e.g. `META-INF/BNDLTOOL.RSA` — bundletool's signer name, when a v1 signature exists. */
    val v1SignerFile: String? = null,
) {
    /** Every certificate this APK can legitimately be matched against. */
    val allSha256: Set<String> get() = (listOfNotNull(signer) + lineage).mapTo(HashSet()) { it.sha256 }
}

enum class SignatureMatch { MATCH, MISMATCH, NOT_INSTALLED, UNKNOWN }

/**
 * Reads who signed an APK, without verifying the signature itself — the package manager
 * does that. What matters here is predicting its verdict before a 500 MB write: an update
 * signed by a different key fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and a bundle
 * whose splits disagree on their signer was repacked and will be refused outright.
 */
object ApkSignatures {

    private const val BLOCK_MAGIC = "APK Sig Block 42"
    private const val ID_V2 = 0x7109871a
    private const val ID_V3 = -0xfac9740            // 0xf05368c0
    private const val ID_V31 = 0x1b93ad61
    private const val ATTR_PROOF_OF_ROTATION = 0x3ba06f8c

    fun read(apk: ZipReader): ApkSignature? {
        val blocks = runCatching { signingBlock(apk) }.getOrDefault(emptyMap())
        val schemes = LinkedHashSet<SignatureScheme>()

        var signer: CertInfo? = null
        var lineage: List<CertInfo> = emptyList()

        blocks[ID_V2]?.let { v ->
            schemes += SignatureScheme.V2
            signer = firstSigner(v, isV3 = false)?.first ?: signer
        }
        blocks[ID_V3]?.let { v ->
            schemes += SignatureScheme.V3
            firstSigner(v, isV3 = true)?.let { (cert, rotation) ->
                if (cert != null) signer = cert
                lineage = rotation
            }
        }
        blocks[ID_V31]?.let { v ->
            schemes += SignatureScheme.V31
            firstSigner(v, isV3 = true)?.let { (cert, rotation) ->
                if (cert != null) signer = cert
                if (rotation.isNotEmpty()) lineage = rotation
            }
        }

        val v1Entry = apk.entries.firstOrNull { e ->
            val n = e.name.uppercase()
            n.startsWith("META-INF/") && n.count { it == '/' } == 1 &&
                (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
        }
        if (v1Entry != null) {
            schemes += SignatureScheme.V1
            if (signer == null) {
                signer = runCatching {
                    val bytes = apk.readAll(v1Entry, 1L shl 20)
                    val certs = CertificateFactory.getInstance("X.509")
                        .generateCertificates(ByteArrayInputStream(bytes))
                    (certs.firstOrNull() as? X509Certificate)?.let { certInfo(it.encoded) }
                }.getOrNull()
            }
        }

        if (schemes.isEmpty()) return null
        return ApkSignature(schemes, signer, lineage.filter { it.sha256 != signer?.sha256 }, v1Entry?.name)
    }

    /** SHA-256 of every certificate the installed copy can be matched against. */
    fun installedSigners(context: Context, packageName: String): Set<String>? = runCatching {
        installedSignatures(context, packageName).mapTo(HashSet()) { sha256Hex(it.toByteArray()) }
    }.getOrNull()

    /** The installed copy's current signer, for display. */
    fun installedSigner(context: Context, packageName: String): CertInfo? = runCatching {
        val pm = context.packageManager
        val current: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        current?.firstOrNull()?.let { certInfo(it.toByteArray()) }
    }.getOrNull()

    fun compare(context: Context, packageName: String?, bundleCerts: Set<String>): SignatureMatch {
        if (packageName == null || bundleCerts.isEmpty()) return SignatureMatch.UNKNOWN
        val installed = runCatching { context.packageManager.getPackageInfo(packageName, 0) }.getOrNull()
            ?: return SignatureMatch.NOT_INSTALLED
        val signers = installedSigners(context, installed.packageName) ?: return SignatureMatch.UNKNOWN
        if (signers.isEmpty()) return SignatureMatch.UNKNOWN
        return if (signers.any { it in bundleCerts }) SignatureMatch.MATCH else SignatureMatch.MISMATCH
    }

    private fun installedSignatures(context: Context, packageName: String): List<Signature> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return emptyList()
            // Both lists: the rotation history is how a legitimately re-keyed update still matches.
            (info.apkContentsSigners.orEmpty().toList() +
                if (info.hasMultipleSigners()) emptyList() else info.signingCertificateHistory.orEmpty().toList())
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty().toList()
        }
    }

    // ---- the APK Signing Block --------------------------------------------

    /** ID → value of every pair in the signing block, which sits right before the central directory. */
    private fun signingBlock(apk: ZipReader): Map<Int, ByteBuffer> {
        val cd = apk.centralDirectoryOffset
        if (cd < 32) return emptyMap()
        val src = apk.source

        val footer = ByteArray(24)
        ZipReader.readFully(src, cd - 24, footer, 0, 24)
        if (String(footer, 8, 16, Charsets.US_ASCII) != BLOCK_MAGIC) return emptyMap()

        val sizeInFooter = ZipReader.le(footer).getLong(0)
        if (sizeInFooter < 24 || sizeInFooter > 64L * 1024 * 1024) return emptyMap()
        val start = cd - sizeInFooter - 8
        if (start < 0) return emptyMap()

        val block = ByteArray((sizeInFooter + 8).toInt())
        ZipReader.readFully(src, start, block, 0, block.size)
        val bb = ZipReader.le(block)
        if (bb.getLong(0) != sizeInFooter) throw IOException("signing block sizes disagree")

        val pairs = HashMap<Int, ByteBuffer>()
        var p = 8
        val end = block.size - 24
        while (p + 12 <= end) {
            val len = bb.getLong(p)
            if (len < 4 || len > end - p - 8L) break
            val id = bb.getInt(p + 8)
            pairs[id] = ByteBuffer.wrap(block, p + 12, (len - 4).toInt()).slice().order(ByteOrder.LITTLE_ENDIAN)
            p += 8 + len.toInt()
        }
        return pairs
    }

    /**
     * First signer's certificate from a v2/v3 block, plus the v3 rotation lineage.
     *
     * Both schemes lay signed data out as digests, then certificates; v3 appends min/max SDK
     * and a set of attributes, one of which is the proof-of-rotation.
     */
    private fun firstSigner(value: ByteBuffer, isV3: Boolean): Pair<CertInfo?, List<CertInfo>>? {
        val signers = value.duplicate().order(ByteOrder.LITTLE_ENDIAN).lengthPrefixed()
        while (signers.hasRemaining()) {
            val signer = signers.lengthPrefixed()
            val signedData = signer.lengthPrefixed()
            signedData.lengthPrefixed() // digests
            val certs = signedData.lengthPrefixed()
            val cert = if (certs.hasRemaining()) certInfo(certs.lengthPrefixed().bytes()) else null

            var lineage = emptyList<CertInfo>()
            if (isV3 && signedData.remaining() >= 8) {
                signedData.int // minSdk
                signedData.int // maxSdk
                if (signedData.remaining() >= 4) {
                    lineage = runCatching { rotationLineage(signedData.lengthPrefixed()) }.getOrDefault(emptyList())
                }
            }
            return cert to lineage
        }
        return null
    }

    private fun rotationLineage(attributes: ByteBuffer): List<CertInfo> {
        while (attributes.hasRemaining()) {
            val attr = attributes.lengthPrefixed()
            if (attr.remaining() < 4) continue
            if (attr.int != ATTR_PROOF_OF_ROTATION) continue

            attr.int // version
            val out = ArrayList<CertInfo>()
            while (attr.hasRemaining()) {
                val level = attr.lengthPrefixed()
                val signedData = level.lengthPrefixed()
                certInfo(signedData.lengthPrefixed().bytes())?.let(out::add)
            }
            return out
        }
        return emptyList()
    }

    private fun ByteBuffer.lengthPrefixed(): ByteBuffer {
        if (remaining() < 4) throw IOException("truncated signing block")
        val len = int
        if (len < 0 || len > remaining()) throw IOException("bad length $len in signing block")
        val slice = slice().order(ByteOrder.LITTLE_ENDIAN)
        slice.limit(len)
        position(position() + len)
        return slice
    }

    private fun ByteBuffer.bytes(): ByteArray = ByteArray(remaining()).also { get(it) }

    fun certInfo(der: ByteArray): CertInfo? = runCatching {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        CertInfo(
            sha256 = sha256Hex(der),
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            notBefore = cert.notBefore.time,
            notAfter = cert.notAfter.time,
        )
    }.getOrNull()

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
