package com.sideload.splitinstaller.core.bundle

import android.content.Context
import android.os.Build
import java.util.Locale

/** Why a split was picked, as a code the UI can put into words. */
data class SplitNote(val code: String, val arg: String? = null) {
    companion object {
        const val BASE = "base"
        const val STANDALONE = "standalone"
        const val ABI = "abi"
        const val DENSITY = "density"
        const val DENSITY_ANY = "density_any"
        const val LANGUAGE = "language"
        const val LANGUAGE_FALLBACK = "language_fallback"
        const val FEATURE = "feature"
        const val UNCLASSIFIED = "unclassified"
    }
}

/**
 * Decides which APKs out of a bundle belong on *this* device.
 *
 * The one rule that matters: whichever `config.<abi>` split the device can actually run
 * must go in. That split is the only place `lib/<abi>/` shared objects live, and an install
 * without it is the install that reports the right version and then dies on `dlopen`.
 */
object SplitSelector {

    data class Choice(
        val selected: Set<String>,
        val notes: Map<String, SplitNote>,
    )

    fun deviceAbis(): List<String> = Build.SUPPORTED_ABIS.toList()

    fun deviceDensity(context: Context): Int = context.resources.displayMetrics.densityDpi

    fun deviceLocales(context: Context): List<String> {
        val list = context.resources.configuration.locales
        return (0 until list.size()).map { list[it].language.lowercase(Locale.ROOT) }
    }

    fun autoSelect(context: Context, info: BundleInfo): Choice {
        val selected = LinkedHashSet<String>()
        val notes = LinkedHashMap<String, SplitNote>()

        val base = info.apks.filter { it.kind == SplitKind.BASE }
        if (base.isNotEmpty()) {
            base.forEach { selected += it.id; notes[it.id] = SplitNote(SplitNote.BASE) }
        } else {
            // Only standalones: exactly one fat APK can be installed, so pick the best fit.
            bestStandalone(context, info)?.let {
                selected += it.id
                notes[it.id] = SplitNote(SplitNote.STANDALONE)
            }
        }

        // ---- ABI: the split that decides whether native code loads at all --------
        val abiSplits = info.apks.filter { it.kind == SplitKind.ABI }
        if (abiSplits.isNotEmpty()) {
            val wanted = deviceAbis().firstOrNull { abi ->
                abiSplits.any { it.targetAbi == abi || abi in it.abis }
            }
            if (wanted != null) {
                abiSplits.filter { it.targetAbi == wanted || wanted in it.abis }.forEach {
                    selected += it.id
                    notes[it.id] = SplitNote(SplitNote.ABI, wanted)
                }
            }
        }

        // ---- density -------------------------------------------------------------
        val densitySplits = info.apks.filter { it.kind == SplitKind.DENSITY }
        if (densitySplits.isNotEmpty()) {
            val dpi = deviceDensity(context)
            val scalable = densitySplits.filter { (it.densityDpi ?: 0) > 0 }
            val best = scalable.filter { (it.densityDpi ?: 0) >= dpi }.minByOrNull { it.densityDpi ?: 0 }
                ?: scalable.maxByOrNull { it.densityDpi ?: 0 }
            best?.let { selected += it.id; notes[it.id] = SplitNote(SplitNote.DENSITY, dpi.toString()) }
            densitySplits.filter { (it.densityDpi ?: 0) == 0 }.forEach {
                selected += it.id
                notes[it.id] = SplitNote(SplitNote.DENSITY_ANY)
            }
        }

        // ---- locale --------------------------------------------------------------
        val localeSplits = info.apks.filter { it.kind == SplitKind.LOCALE }
        if (localeSplits.isNotEmpty()) {
            val langs = (deviceLocales(context) + "en").distinct()
            var matched = false
            localeSplits.forEach { split ->
                val lang = split.locale?.substringBefore('_')?.substringBefore('-')
                    ?.removePrefix("b+")?.lowercase(Locale.ROOT)
                if (lang != null && lang in langs) {
                    selected += split.id
                    notes[split.id] = SplitNote(SplitNote.LANGUAGE, lang)
                    matched = true
                }
            }
            // Never ship an app with no strings at all.
            if (!matched) localeSplits.firstOrNull()?.let {
                selected += it.id
                notes[it.id] = SplitNote(SplitNote.LANGUAGE_FALLBACK)
            }
        }

        // ---- feature modules and asset packs -------------------------------------
        info.apks.filter { it.kind == SplitKind.FEATURE || it.kind == SplitKind.UNKNOWN }.forEach {
            selected += it.id
            notes[it.id] = SplitNote(if (it.kind == SplitKind.FEATURE) SplitNote.FEATURE else SplitNote.UNCLASSIFIED)
        }

        return Choice(selected, notes)
    }

    private fun bestStandalone(context: Context, info: BundleInfo): SplitApk? {
        val standalones = info.apks.filter { it.kind == SplitKind.STANDALONE }
        if (standalones.size <= 1) return standalones.firstOrNull()
        val dpi = deviceDensity(context)
        val abis = deviceAbis()
        return standalones.minByOrNull { apk ->
            val abiRank = apk.abis.minOfOrNull { abis.indexOf(it).takeIf { i -> i >= 0 } ?: 99 }
                ?: fileNameAbiRank(apk.fileName, abis)
            val dpiRank = fileNameDensity(apk.fileName)?.let { kotlin.math.abs(it - dpi) } ?: 10_000
            abiRank * 100_000 + dpiRank
        }
    }

    private fun fileNameAbiRank(name: String, abis: List<String>): Int {
        BundleInspector.ABI_TOKENS.forEach { (token, abi) ->
            if (name.contains(token)) return abis.indexOf(abi).takeIf { it >= 0 } ?: 99
        }
        return 99
    }

    private fun fileNameDensity(name: String): Int? =
        BundleInspector.DENSITY_TOKENS.entries
            .filter { it.value > 0 && name.contains(it.key) }
            .maxByOrNull { it.key.length }
            ?.value

    /**
     * A selection is only installable if it carries a base and, when the bundle has ABI
     * splits at all, one this device can run.
     */
    fun validate(info: BundleInfo, selected: Set<String>): List<Finding> {
        val out = ArrayList<Finding>()
        val chosen = info.apks.filter { it.id in selected }

        if (chosen.none { it.kind == SplitKind.BASE || it.kind == SplitKind.STANDALONE }) {
            out += Finding(Severity.ERROR, "no base APK selected — the install cannot start", FindingCode.SEL_NO_BASE)
        }

        val bundleHasAbiSplits = info.apks.any { it.kind == SplitKind.ABI }
        val chosenAbis = chosen.flatMapTo(HashSet()) { it.abis }
        val deviceAbi = deviceAbis().firstOrNull { it in chosenAbis }

        if (bundleHasAbiSplits && deviceAbi == null) {
            val offered = info.apks.filter { it.kind == SplitKind.ABI }
                .mapNotNull { it.targetAbi }.distinct()
            out += if (offered.any { it in deviceAbis() }) {
                Finding(
                    Severity.ERROR,
                    "the ABI split for this device is deselected — the app will install, " +
                        "report the right version, and crash on launch with a missing .so",
                    FindingCode.SEL_ABI_DESELECTED,
                )
            } else {
                Finding(
                    Severity.ERROR,
                    "bundle only has native code for " + offered.joinToString() +
                        "; this device is " + deviceAbis().joinToString(),
                    FindingCode.SEL_ABI_UNSUPPORTED,
                    listOf(offered.joinToString(), deviceAbis().joinToString()),
                )
            }
        }
        return out
    }
}
