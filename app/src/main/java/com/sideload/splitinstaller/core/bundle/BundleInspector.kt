package com.sideload.splitinstaller.core.bundle

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.sideload.splitinstaller.core.axml.ApkManifest
import com.sideload.splitinstaller.core.axml.AxmlParser
import com.sideload.splitinstaller.core.sign.ApkSignature
import com.sideload.splitinstaller.core.sign.ApkSignatures
import com.sideload.splitinstaller.core.zip.ChannelSource
import com.sideload.splitinstaller.core.zip.DataSource
import com.sideload.splitinstaller.core.zip.ZipEntryInfo
import com.sideload.splitinstaller.core.zip.ZipReader
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream

/** Immutable byte holder with identity equality, so it can ride along in a data class. */
class IconBytes(val bytes: ByteArray)

object BundleInspector {

    val ABI_TOKENS = mapOf(
        "armeabi" to "armeabi",
        "armeabi_v7a" to "armeabi-v7a",
        "arm64_v8a" to "arm64-v8a",
        "x86" to "x86",
        "x86_64" to "x86_64",
        "mips" to "mips",
        "mips64" to "mips64",
    )

    val DENSITY_TOKENS = mapOf(
        "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
        "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
        "nodpi" to 0, "anydpi" to 0,
    )

    private val LOCALE_RE = Regex("^(b\\+[A-Za-z0-9+]+|[a-z]{2,3}([_-][A-Za-z0-9]{2,8})*)$")

    // ---- opening -----------------------------------------------------------

    /**
     * A seekable view of the bundle. Content providers usually hand back a real file
     * descriptor; when one turns out to be a pipe instead, fall back to a cache copy so
     * the zip's central directory is still reachable.
     */
    fun openSource(context: Context, uri: Uri): DataSource {
        if (uri.scheme == "file" || uri.scheme == null) {
            val f = File(requireNotNull(uri.path) { "file uri without a path" })
            return ChannelSource(FileInputStream(f).channel)
        }
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("could not open $uri")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val channel = stream.channel
        val seekable = runCatching { channel.size() > 0 }.getOrDefault(false)
        if (seekable) return ChannelSource(channel, stream)

        // Not seekable. Spool it out once, then read normally.
        stream.close()
        val cache = File(context.cacheDir, "spool").apply { mkdirs() }
        val tmp = File(cache, "bundle-" + System.currentTimeMillis() + ".zip")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "could not read $uri" }
            tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
        }
        return object : DataSource {
            private val inner = ChannelSource(FileInputStream(tmp).channel)
            override val size = inner.size
            override fun readAt(position: Long, dst: ByteArray, off: Int, len: Int) =
                inner.readAt(position, dst, off, len)
            override fun close() { inner.close(); tmp.delete() }
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file" || uri.scheme == null) return File(uri.path.orEmpty()).name
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "bundle"
    }

    // ---- inspection --------------------------------------------------------

    fun inspect(context: Context, uri: Uri): BundleInfo {
        val name = displayName(context, uri)
        val source = openSource(context, uri)
        return try {
            if (name.lowercase(Locale.ROOT).endsWith(".apk")) {
                singleApk(uri, name, source)
            } else {
                ZipReader(source, ownsSource = false).use { zip -> bundle(uri, name, zip, source.size) }
            }
        } finally {
            source.close()
        }
    }

    private fun singleApk(uri: Uri, name: String, source: DataSource): BundleInfo {
        val zip = ZipReader(source, ownsSource = false)
        val probe = zip.use { inspectApk(it) }
        val apk = SplitApk(
            entryName = "", fileName = name, size = source.size, kind = SplitKind.BASE,
            packageName = probe.manifest.packageName, versionCode = probe.manifest.versionCode,
            versionName = probe.manifest.versionName, minSdk = probe.manifest.minSdk,
            abis = probe.abis, nativeLibCount = probe.libCount, inspected = true,
            signature = probe.signature,
        )
        return BundleInfo(
            uri = uri, displayName = name, totalSize = source.size, format = BundleFormat.APK,
            packageName = probe.manifest.packageName, appLabel = null,
            versionCode = probe.manifest.versionCode, versionName = probe.manifest.versionName,
            minSdk = probe.manifest.minSdk, apks = listOf(apk), obbs = emptyList(),
            findings = check(listOf(apk), probe.manifest.minSdk),
            engine = probe.manifest.engine,
        )
    }

    private fun bundle(
        uri: Uri,
        name: String,
        zip: ZipReader,
        totalSize: Long,
    ): BundleInfo {
        val meta = readSidecarMetadata(zip)
        val format = detectFormat(name, zip, meta)

        val apkEntries = zip.entries.filter { e ->
            !e.isDirectory &&
                e.name.endsWith(".apk", ignoreCase = true) &&
                !e.name.startsWith("META-INF/", ignoreCase = true) &&
                !e.name.contains("/META-INF/", ignoreCase = true)
        }
        if (apkEntries.isEmpty()) throw IOException("no APK found inside $name")

        // bundletool ships both forms. Splits win; standalones are the no-splits fallback.
        val hasSplitsDir = apkEntries.any { it.name.startsWith("splits/") }
        val usable = if (hasSplitsDir) apkEntries.filterNot { it.name.startsWith("standalones/") } else apkEntries

        val described = usable.map { entry -> describe(zip, entry) }
        val apks = described.map { it.first }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.fileName }))
        val engine = described.firstOrNull { it.first.kind == SplitKind.BASE }?.second?.engine
            ?: described.firstNotNullOfOrNull { it.second?.engine }

        val basePkg = apks.firstOrNull { it.kind == SplitKind.BASE }?.packageName
        val pkg = basePkg
            ?: apks.firstNotNullOfOrNull { it.packageName }
            ?: meta?.optString("package_name")?.ifBlank { null }
            ?: meta?.optString("pname")?.ifBlank { null }

        val baseApk = apks.firstOrNull { it.kind == SplitKind.BASE }
        val versionCode = baseApk?.versionCode?.takeIf { it > 0 }
            ?: meta?.optString("version_code")?.toLongOrNull()
            ?: meta?.optLong("versioncode")?.takeIf { it > 0 }
            ?: 0L
        val versionName = baseApk?.versionName
            ?: meta?.optString("version_name")?.ifBlank { null }
            ?: meta?.optString("release_version")?.ifBlank { null }
        val minSdk = apks.maxOfOrNull { it.minSdk }?.takeIf { it > 0 }
            ?: meta?.optString("min_sdk_version")?.toIntOrNull()
            ?: meta?.optInt("min_api")?.takeIf { it > 0 }
            ?: 0
        val label = meta?.optString("name")?.ifBlank { null }
            ?: meta?.optString("app_name")?.ifBlank { null }
            ?: meta?.optString("apk_title")?.ifBlank { null }

        return BundleInfo(
            uri = uri,
            displayName = name,
            totalSize = totalSize,
            format = format,
            packageName = pkg,
            appLabel = label,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            apks = apks,
            icon = readIcon(zip),
            obbs = collectObb(zip, meta, pkg),
            findings = check(apks, minSdk, engine),
            engine = engine,
        )
    }

    // ---- per-APK -----------------------------------------------------------

    private class Probe(
        val manifest: ApkManifest,
        val abis: Set<String>,
        val libCount: Int,
        val signature: ApkSignature? = null,
    )

    private fun describe(zip: ZipReader, entry: ZipEntryInfo): Pair<SplitApk, ApkManifest?> {
        val fileName = entry.name.substringAfterLast('/')
        val probe = runCatching { inspectApk(zip, entry) }.getOrNull()

        val splitName = probe?.manifest?.splitName?.ifBlank { null }
            ?: guessSplitName(entry.name, fileName)
        val kind = classify(entry.name, splitName, probe)
        val suffix = splitName?.substringAfterLast('.')

        return SplitApk(
            entryName = entry.name,
            fileName = fileName,
            size = entry.size,
            kind = kind,
            signature = probe?.signature,
            splitName = splitName,
            packageName = probe?.manifest?.packageName,
            versionCode = probe?.manifest?.versionCode ?: 0,
            versionName = probe?.manifest?.versionName,
            minSdk = probe?.manifest?.minSdk ?: 0,
            abis = probe?.abis ?: abisFromName(entry.name),
            targetAbi = ABI_TOKENS[suffix],
            densityDpi = DENSITY_TOKENS[suffix],
            locale = suffix?.takeIf { kind == SplitKind.LOCALE },
            nativeLibCount = probe?.libCount ?: 0,
            inspected = probe != null,
            crc = entry.crc,
        ) to probe?.manifest
    }

    /** Read a nested APK's manifest and lib layout without unpacking it. */
    private fun inspectApk(zip: ZipReader, entry: ZipEntryInfo): Probe {
        zip.nested(entry)?.use { return inspectApk(it) }
        // Deflated nested APK: no random access, so walk it as a stream instead.
        return scanApkStream(zip, entry)
    }

    private fun inspectApk(apk: ZipReader): Probe {
        val abis = LinkedHashSet<String>()
        var libCount = 0
        for (e in apk.entries) {
            val n = e.name
            if (!n.startsWith("lib/")) continue
            val slash = n.indexOf('/', 4)
            if (slash <= 4) continue
            abis.add(n.substring(4, slash))
            if (n.endsWith(".so")) libCount++
        }
        val manifest = apk["AndroidManifest.xml"]
            ?.let { runCatching { AxmlParser.parse(apk.readAll(it, 8L * 1024 * 1024)) }.getOrNull() }
            ?: ApkManifest()
        val signature = runCatching { ApkSignatures.read(apk) }.getOrNull()
        return Probe(manifest, abis, libCount, signature)
    }

    private fun scanApkStream(zip: ZipReader, entry: ZipEntryInfo): Probe {
        val abis = LinkedHashSet<String>()
        var libCount = 0
        var manifest = ApkManifest()
        val deadline = System.nanoTime() + 20_000_000_000L
        var seen = 0
        ZipInputStream(zip.open(entry).buffered(1 shl 18)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val n = e.name
                if (n.startsWith("lib/")) {
                    val slash = n.indexOf('/', 4)
                    if (slash > 4) abis.add(n.substring(4, slash))
                    if (n.endsWith(".so")) libCount++
                } else if (n == "AndroidManifest.xml") {
                    manifest = runCatching { AxmlParser.parse(zin.readBytes()) }.getOrElse { manifest }
                }
                if (++seen > 50_000 || System.nanoTime() > deadline) break
            }
        }
        return Probe(manifest, abis, libCount)
    }

    // ---- classification ----------------------------------------------------

    private fun classify(entryName: String, splitName: String?, probe: Probe?): SplitKind {
        if (entryName.startsWith("standalones/")) return SplitKind.STANDALONE
        if (splitName.isNullOrEmpty()) return SplitKind.BASE
        val suffix = splitName.substringAfterLast('.')
        return when {
            suffix in ABI_TOKENS -> SplitKind.ABI
            suffix in DENSITY_TOKENS -> SplitKind.DENSITY
            splitName.contains("config.") && LOCALE_RE.matches(suffix) -> SplitKind.LOCALE
            probe?.manifest?.isFeatureSplit == true -> SplitKind.FEATURE
            splitName.contains("config.") -> SplitKind.UNKNOWN
            else -> SplitKind.FEATURE
        }
    }

    /**
     * Last resort when a nested manifest will not parse. Covers the three naming schemes in
     * the wild: APKPure's `config.*`, the platform's `split_config.*`, and bundletool's
     * `base-*` / `<module>-*`.
     */
    private fun guessSplitName(entryName: String, fileName: String): String? {
        val stem = fileName.removeSuffix(".apk").removeSuffix(".APK")
        if (stem.equals("base", true) || stem.equals("universal", true)) return null
        if (stem.equals("base-master", true) || stem.endsWith("-master", true)) return null

        stem.removePrefix("split_").let { s ->
            if (s.startsWith("config.")) return s
        }
        if (stem.startsWith("config.")) return stem

        // bundletool: `base-arm64_v8a.apk`, `assetpack-xxhdpi.apk`
        if (entryName.startsWith("splits/") && '-' in stem) {
            val token = stem.substringAfterLast('-')
            if (token in ABI_TOKENS || token in DENSITY_TOKENS || LOCALE_RE.matches(token)) {
                return "config.$token"
            }
            return stem.substringBeforeLast('-')
        }
        // Anything else, including `<pkg>.apk`, is how XAPK names its base.
        return null
    }

    private fun abisFromName(entryName: String): Set<String> {
        val hit = ABI_TOKENS.entries.firstOrNull { (token, _) -> entryName.contains(token) }
        return hit?.let { setOf(it.value) } ?: emptySet()
    }

    // ---- sidecar metadata, OBB, findings -----------------------------------

    private fun readIcon(zip: ZipReader): IconBytes? {
        val entry = zip["icon.png"] ?: zip["icon.jpg"] ?: return null
        return runCatching { IconBytes(zip.readAll(entry, 4L * 1024 * 1024)) }.getOrNull()
    }

    private fun readSidecarMetadata(zip: ZipReader): JSONObject? {
        val entry = zip["manifest.json"] ?: zip["info.json"] ?: return null
        return runCatching {
            JSONObject(String(zip.readAll(entry, 4L * 1024 * 1024), Charsets.UTF_8))
        }.getOrNull()
    }

    private fun detectFormat(name: String, zip: ZipReader, meta: JSONObject?): BundleFormat {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".xapk") -> BundleFormat.XAPK
            lower.endsWith(".apkm") -> BundleFormat.APKM
            lower.endsWith(".apks") -> BundleFormat.APKS
            zip["manifest.json"] != null && meta?.has("split_apks") == true -> BundleFormat.XAPK
            zip["info.json"] != null && meta?.has("pname") == true -> BundleFormat.APKM
            zip["toc.pb"] != null || zip.entries.any { it.name.startsWith("splits/") } -> BundleFormat.APKS
            else -> BundleFormat.ZIP
        }
    }

    private fun collectObb(zip: ZipReader, meta: JSONObject?, pkg: String?): List<ObbFile> {
        val out = LinkedHashMap<String, ObbFile>()

        // XAPK records expansion files explicitly, including where they must land.
        meta?.optJSONArray("expansions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val file = o.optString("file").takeIf { it.isNotBlank() } ?: continue
                val entry = zip[file] ?: continue
                val target = o.optString("install_path").ifBlank { file }
                out[file] = ObbFile(file, file.substringAfterLast('/'), entry.size, target.trimStart('/'))
            }
        }
        // Anything sitting under Android/obb inside the zip, recorded or not.
        for (e in zip.entries) {
            if (e.isDirectory || e.name in out) continue
            val n = e.name.trimStart('/')
            if (!n.startsWith("Android/obb/", ignoreCase = true)) continue
            out[e.name] = ObbFile(e.name, n.substringAfterLast('/'), e.size, n)
        }
        // A loose .obb with nowhere stated: infer the standard location.
        if (out.isEmpty() && pkg != null) {
            for (e in zip.entries) {
                if (e.isDirectory || !e.name.endsWith(".obb", true)) continue
                val f = e.name.substringAfterLast('/')
                out[e.name] = ObbFile(e.name, f, e.size, "Android/obb/$pkg/$f")
            }
        }
        return out.values.toList()
    }

    private fun check(
        apks: List<SplitApk>,
        minSdk: Int,
        engine: com.sideload.splitinstaller.core.axml.NativeEngine? = null,
    ): List<Finding> {
        val findings = ArrayList<Finding>()

        if (apks.none { it.kind == SplitKind.BASE || it.kind == SplitKind.STANDALONE }) {
            findings += Finding(Severity.ERROR, "no base APK in this bundle", FindingCode.NO_BASE)
        }

        val deviceAbis = Build.SUPPORTED_ABIS.toList()
        val bundleAbis = apks.flatMapTo(LinkedHashSet()) { it.abis }
        val nativeSplits = apks.filter { it.kind == SplitKind.ABI }

        if (bundleAbis.isNotEmpty() && deviceAbis.none { it in bundleAbis }) {
            findings += Finding(
                Severity.ERROR,
                "bundle ships native code for ${bundleAbis.joinToString()} but this device is " +
                    deviceAbis.joinToString() + " — it will not run",
                FindingCode.ABI_MISMATCH,
                listOf(bundleAbis.joinToString(), deviceAbis.joinToString()),
            )
        } else if (bundleAbis.isEmpty() && engine != null) {
            findings += Finding(
                Severity.ERROR,
                "this is a ${engine.label} app but the bundle has no lib/<abi>/ at all — it is " +
                    "base-only and will fail at launch with a missing .so. Get a complete bundle.",
                FindingCode.ENGINE_NO_LIBS,
                listOf(engine.label),
            )
        } else if (nativeSplits.isEmpty() && bundleAbis.isEmpty() && apks.size > 1) {
            findings += Finding(
                Severity.WARN,
                "no lib/<abi>/ anywhere in this bundle — if the app uses native code, " +
                    "this download is base-only and will fail at launch",
                FindingCode.NO_LIBS,
            )
        }

        val signers = apks.mapNotNull { it.signature?.signer }.distinctBy { it.sha256 }
        if (signers.size > 1) {
            findings += Finding(
                Severity.ERROR,
                "the APKs are signed by ${signers.size} different certificates — the bundle was " +
                    "repacked, and the installer rejects splits whose signers disagree",
                FindingCode.SIGNERS_DIFFER,
                listOf(signers.size.toString()),
            )
        }

        if (minSdk > Build.VERSION.SDK_INT) {
            findings += Finding(
                Severity.ERROR,
                "needs Android API $minSdk, this device is API ${Build.VERSION.SDK_INT}",
                FindingCode.MIN_SDK,
                listOf(minSdk.toString(), Build.VERSION.SDK_INT.toString()),
            )
        }

        val pkgs = apks.mapNotNull { it.packageName }.toSet()
        if (pkgs.size > 1) {
            findings += Finding(
                Severity.ERROR,
                "APKs disagree on package name: " + pkgs.joinToString(),
                FindingCode.PKG_DISAGREE,
                listOf(pkgs.joinToString()),
            )
        }
        val codes = apks.filter { it.inspected }.map { it.versionCode }.filter { it > 0 }.toSet()
        if (codes.size > 1) {
            findings += Finding(
                Severity.ERROR,
                "splits disagree on versionCode (" + codes.sorted().joinToString() +
                    ") — the installer will reject them",
                FindingCode.VERSION_DISAGREE,
                listOf(codes.sorted().joinToString()),
            )
        }
        if (apks.any { !it.inspected }) {
            findings += Finding(
                Severity.INFO,
                "some APKs could not be read directly; their roles were guessed from file names",
                FindingCode.GUESSED,
            )
        }
        return findings
    }
}
