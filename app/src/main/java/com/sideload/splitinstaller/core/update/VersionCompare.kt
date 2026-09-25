package com.sideload.splitinstaller.core.update

/**
 * Compares the version strings apps actually ship.
 *
 * Only used where a source has no versionCode to offer — GitHub tags, mostly. Anything it
 * cannot read confidently comes back as null, which the UI reports as "could not tell"
 * rather than quietly claiming the app is up to date.
 */
object VersionCompare {

    private val SEPARATORS = Regex("[._\\-+~ ]")

    /** Leading numeric parts: "v2.3.0-beta.1" gives [2, 3, 0]. */
    fun numbers(version: String): List<Int> {
        val cleaned = version.trim().removePrefix("v").removePrefix("V").substringBefore(' ')
        val out = ArrayList<Int>()
        for (token in cleaned.split(SEPARATORS)) {
            val digits = token.takeWhile { it.isDigit() }
            if (digits.isEmpty()) break
            out += digits.toIntOrNull() ?: break
            if (digits.length != token.length) break // "1rc2" ends the numeric run
        }
        return out
    }

    /** True when the version carries a pre-release marker, which sorts below the plain form. */
    fun isPreRelease(version: String): Boolean {
        val v = version.lowercase()
        return listOf("alpha", "beta", "rc", "preview", "dev", "snapshot", "canary", "early access")
            .any { it in v }
    }

    /**
     * -1, 0 or 1 in the usual sense, or null when neither side has a readable number.
     */
    fun compare(a: String?, b: String?): Int? {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return null
        val left = numbers(a)
        val right = numbers(b)
        if (left.isEmpty() || right.isEmpty()) return null

        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return if (l > r) 1 else -1
        }
        val lPre = isPreRelease(a)
        val rPre = isPreRelease(b)
        return when {
            lPre == rPre -> 0
            lPre -> -1
            else -> 1
        }
    }

    /** Null when the two cannot be compared, so the caller can say so instead of guessing. */
    fun isNewer(available: String?, installed: String?): Boolean? =
        compare(available, installed)?.let { it > 0 }
}
