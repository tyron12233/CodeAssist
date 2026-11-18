package dev.codeassist.csharp.project.nuget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuGetVersionTest {

    private fun v(text: String) = NuGetVersion.parse(text)!!

    @Test
    fun `a version is normalized to three numbers`() {
        assertEquals("1.0.0", v("1").normalized)
        assertEquals("1.2.0", v("1.2").normalized)
        assertEquals("13.0.3", v("13.0.3").normalized)
        assertEquals("1.2.3.4", v("1.2.3.4").normalized)
        assertEquals("1.2.3", v("1.2.3.0").normalized)
    }

    @Test
    fun `build metadata is dropped and a prerelease label is lowercased`() {
        assertEquals("1.0.0", v("1.0.0+abc").normalized)
        assertEquals("2.0.0-beta.1", v("2.0.0-Beta.1+x").normalized)
    }

    @Test
    fun `not a version gives null`() {
        assertNull(NuGetVersion.parse("abc"))
        assertNull(NuGetVersion.parse("1.x"))
        assertNull(NuGetVersion.parse("1.2.3.4.5"))
        assertNull(NuGetVersion.parse(""))
    }

    @Test
    fun `numbers compare as numbers`() {
        assertTrue(v("1.10.0") > v("1.9.0"))
        assertTrue(v("2.0") > v("1.99.99"))
        assertEquals(v("1.0"), v("1.0.0.0"))
    }

    @Test
    fun `a release is above its prerelease`() {
        assertTrue(v("1.0.0") > v("1.0.0-rc.1"))
        assertTrue(v("1.0.0-rc.2") > v("1.0.0-rc.1"))
        assertTrue(v("1.0.0-rc.10") > v("1.0.0-rc.2"))
        assertTrue(v("1.0.0-beta") > v("1.0.0-alpha"))
        assertTrue(v("1.0.0-alpha.1") > v("1.0.0-alpha"))
        assertTrue(v("1.0.0-alpha.beta") > v("1.0.0-alpha.1"))
    }

    @Test
    fun `a range gives its lowest version`() {
        assertEquals("1.2.0", NuGetVersion.lowerBound("1.2")!!.normalized)
        assertEquals("1.2.0", NuGetVersion.lowerBound("[1.2,)")!!.normalized)
        assertEquals("1.2.0", NuGetVersion.lowerBound("[1.2, 2.0)")!!.normalized)
        assertEquals("1.2.0", NuGetVersion.lowerBound("(1.2,)")!!.normalized)
        assertEquals("3.0.0", NuGetVersion.lowerBound("[3.0.0]")!!.normalized)
    }

    @Test
    fun `a range with no lower bound or a floating one gives none`() {
        assertNull(NuGetVersion.lowerBound("(,2.0]"))
        assertNull(NuGetVersion.lowerBound("1.*"))
        assertNull(NuGetVersion.lowerBound(""))
    }

    @Test
    fun `floating versions are told apart`() {
        assertTrue(NuGetVersion.isFloating("*"))
        assertTrue(NuGetVersion.isFloating("1.*"))
        assertFalse(NuGetVersion.isFloating("1.0"))
        assertEquals("", NuGetVersion.floatingPrefix("*"))
        assertEquals("1.", NuGetVersion.floatingPrefix("1.*"))
        assertEquals("1.2.", NuGetVersion.floatingPrefix("1.2.*"))
    }

    // ---- frameworks ----

    @Test
    fun `the newest usable framework wins`() {
        assertEquals("net8.0", NuGetFrameworks.best(listOf("netstandard2.0", "net8.0", "net6.0")))
        assertEquals("net10.0", NuGetFrameworks.best(listOf("net10.0", "net9.0")))
        assertEquals("netstandard2.1", NuGetFrameworks.best(listOf("netstandard2.0", "netstandard2.1")))
    }

    @Test
    fun `net standard is the fallback`() {
        assertEquals("netstandard2.0", NuGetFrameworks.best(listOf("net45", "netstandard2.0", "netstandard1.3")))
    }

    @Test
    fun `platform and framework folders are not usable`() {
        assertNull(NuGetFrameworks.best(listOf("net8.0-android", "net472", "net45", "monoandroid10.0")))
        assertNull(NuGetFrameworks.best(emptyList()))
    }

    @Test
    fun `long monikers are shortened and the name as written is kept`() {
        assertEquals("netstandard2.0", NuGetFrameworks.normalize(".NETStandard2.0"))
        assertEquals("netcoreapp3.1", NuGetFrameworks.normalize(".NETCoreApp3.1"))
        assertEquals("net472", NuGetFrameworks.normalize(".NETFramework4.7.2"))
        assertEquals(".NETStandard2.0", NuGetFrameworks.best(listOf(".NETFramework4.5", ".NETStandard2.0")))
    }
}
