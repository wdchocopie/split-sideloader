package com.sideload.splitinstaller

import com.sideload.splitinstaller.core.update.VersionCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test
    fun `reads the leading numbers and stops at the first non-number`() {
        assertEquals(listOf(2, 3, 0), VersionCompare.numbers("v2.3.0-beta.1"))
        assertEquals(listOf(1, 21), VersionCompare.numbers("1.21-rc2"))
        assertEquals(listOf(4), VersionCompare.numbers("4rc2.9"))
        assertEquals(listOf(8, 1, 2), VersionCompare.numbers("  V8.1.2 (build 4471)  "))
        assertEquals(emptyList<Int>(), VersionCompare.numbers("nightly"))
    }

    @Test
    fun `shorter versions are padded, not treated as smaller`() {
        assertEquals(0, VersionCompare.compare("2.3", "2.3.0"))
        assertEquals(1, VersionCompare.compare("2.3.1", "2.3"))
    }

    @Test
    fun `compares numerically, not as text`() {
        assertEquals(1, VersionCompare.compare("1.10.0", "1.9.9"))
        assertEquals(-1, VersionCompare.compare("1.9.9", "1.10.0"))
        assertEquals(1, VersionCompare.compare("v2.0", "1.99.99"))
    }

    @Test
    fun `a pre-release sorts below the same plain version`() {
        assertTrue(VersionCompare.isPreRelease("3.0.0-rc1"))
        assertTrue(VersionCompare.isPreRelease("3.0.0 Canary"))
        assertFalse(VersionCompare.isPreRelease("3.0.0"))
        assertEquals(-1, VersionCompare.compare("3.0.0-beta", "3.0.0"))
        assertEquals(1, VersionCompare.compare("3.0.0", "3.0.0-beta"))
        assertEquals(0, VersionCompare.compare("3.0.0-beta.2", "3.0.0-beta.1"))
    }

    @Test
    fun `says it cannot tell instead of guessing`() {
        assertNull(VersionCompare.compare("nightly", "1.0"))
        assertNull(VersionCompare.compare("1.0", ""))
        assertNull(VersionCompare.compare(null, "1.0"))
        assertNull(VersionCompare.isNewer("latest", "2.0"))
    }

    @Test
    fun `isNewer only says yes for a strictly higher version`() {
        assertEquals(true, VersionCompare.isNewer("2.1.0", "2.0.9"))
        assertEquals(false, VersionCompare.isNewer("2.0.9", "2.1.0"))
        assertEquals(false, VersionCompare.isNewer("2.1.0", "2.1.0"))
        // A tag that only adds a build suffix is not an update.
        assertEquals(false, VersionCompare.isNewer("2.1.0-beta", "2.1.0"))
    }
}
