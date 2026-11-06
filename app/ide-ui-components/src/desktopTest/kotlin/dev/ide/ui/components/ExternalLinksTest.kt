package dev.ide.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalLinksTest {

    @Test
    fun plainHttpsLinkHasNoWarnings() {
        val check = checkLink("https://github.com/tyron12233/CodeAssist/wiki/Plugins")
        assertEquals("github.com", check.target)
        assertTrue(check.warnings.isEmpty())
    }

    @Test
    fun userInfoIsNotTheHost() {
        val check = checkLink("https://github.com@evil.example/login")
        assertEquals("evil.example", check.target)
        assertEquals(listOf(LinkWarning.Disguised), check.warnings)
    }

    @Test
    fun portQueryAndFragmentAreNotPartOfTheHost() {
        assertEquals("example.com", checkLink("https://Example.com:8443?x=1").target)
        assertEquals("example.com", checkLink("https://example.com.#top").target)
    }

    @Test
    fun httpIsNotEncrypted() {
        assertEquals(listOf(LinkWarning.NotEncrypted), checkLink("http://example.com").warnings)
    }

    @Test
    fun rawAddresses() {
        assertEquals(listOf(LinkWarning.RawAddress), checkLink("https://192.168.0.1/admin").warnings)
        val v6 = checkLink("https://[::1]:8080/")
        assertEquals("[::1]", v6.target)
        assertEquals(listOf(LinkWarning.RawAddress), v6.warnings)
    }

    @Test
    fun lookalikeHosts() {
        assertEquals(listOf(LinkWarning.Lookalike), checkLink("https://xn--80ak6aa92e.com/").warnings)
        assertEquals(listOf(LinkWarning.Lookalike), checkLink("https://аpple.com/").warnings) // Cyrillic а
    }

    @Test
    fun mailtoTargetsTheAddress() {
        val check = checkLink("mailto:dev@example.com?subject=hi")
        assertTrue(check.isMail)
        assertEquals("dev@example.com", check.target)
        assertTrue(check.warnings.isEmpty())
    }
}
