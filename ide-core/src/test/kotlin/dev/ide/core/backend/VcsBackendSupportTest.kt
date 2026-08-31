package dev.ide.core.backend

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two pure helpers the version-control backend formats with: which repository a remote URL names, and how
 * old a commit reads. Both feed user-visible strings, and both are easy to get subtly wrong.
 */
class VcsBackendSupportTest {

    @Test
    fun `an https remote resolves to owner and name`() {
        assertEquals("octocat" to "hello", parseSlug("https://github.com/octocat/hello.git"))
        assertEquals("octocat" to "hello", parseSlug("https://github.com/octocat/hello"))
        assertEquals("octocat" to "hello", parseSlug("https://github.com/octocat/hello/"))
    }

    @Test
    fun `an scp-like remote resolves to owner and name`() {
        assertEquals("octocat" to "hello", parseSlug("git@github.com:octocat/hello.git"))
        assertEquals("team" to "app", parseSlug("ssh://git@git.example.com/team/app.git"))
    }

    @Test
    fun `an enterprise path keeps the last two segments`() {
        assertEquals("team" to "app", parseSlug("https://git.example.com/scm/team/app.git"))
    }

    @Test
    fun `a URL with no repository segment resolves to nothing`() {
        assertNull(parseSlug("https://github.com/"))
        assertNull(parseSlug("not-a-url"))
        assertNull(parseSlug(""))
    }

    @Test
    fun `ages read as minutes, hours, and days before falling back to a date`() {
        val now = 1_700_000_000_000L
        assertEquals("", ageLabel(0L, now))
        assertEquals("now", ageLabel(now - 30_000L, now))
        assertEquals("5m", ageLabel(now - 5 * 60_000L, now))
        assertEquals("3h", ageLabel(now - 3 * 3_600_000L, now))
        assertEquals("2d", ageLabel(now - 2 * 86_400_000L, now))
        // Past a week the label becomes a short localized date, which varies by locale but is never blank.
        assertEquals(true, ageLabel(now - 40L * 86_400_000L, now).isNotBlank())
    }
}
