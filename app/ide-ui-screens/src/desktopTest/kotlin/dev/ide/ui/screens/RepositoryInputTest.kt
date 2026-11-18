package dev.ide.ui.screens

import dev.ide.ui.backend.UiRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the Repositories manager makes of a typed or pasted address. */
class RepositoryInputTest {
    private val existing = listOf(
        UiRepository("Maven Central", "https://repo1.maven.org/maven2", builtin = true),
        UiRepository("Google", "https://dl.google.com/dl/android/maven2", builtin = true),
    )

    private fun parse(text: String, name: String = "") = parseRepositoryInput(name, text, existing)

    @Test
    fun `a full address is kept, named after its host`() {
        assertEquals(RepositoryInput.Ok("repo.example.com", "https://repo.example.com/maven", insecure = false), parse("https://repo.example.com/maven/"))
    }

    @Test
    fun `a bare host gets https and a known one gets its name`() {
        assertEquals(RepositoryInput.Ok("JitPack", "https://jitpack.io", insecure = false), parse("jitpack.io"))
        assertEquals(RepositoryInput.Ok("Mine", "https://jitpack.io", insecure = false), parse(" jitpack.io ", name = " Mine "))
    }

    @Test
    fun `a pasted Gradle line yields the address inside it`() {
        assertEquals(
            RepositoryInput.Ok("JitPack", "https://jitpack.io", insecure = false),
            parse("""maven { url = uri("https://jitpack.io") }"""),
        )
        assertEquals(
            RepositoryInput.Ok("plugins.example.com", "https://plugins.example.com/m2", insecure = false),
            parse("maven { url 'https://plugins.example.com/m2/' }"),
        )
    }

    @Test
    fun `plain http is allowed and flagged`() {
        assertEquals(RepositoryInput.Ok("10.0.2.2", "http://10.0.2.2:8081/repo", insecure = true), parse("http://10.0.2.2:8081/repo"))
    }

    @Test
    fun `text that is not an address is refused`() {
        for (text in listOf("", "   ", "jitpack", "my repo", "ftp://example.com/maven", "https://")) {
            assertEquals(RepositoryInput.NotAnAddress, parse(text), "for \"$text\"")
        }
    }

    @Test
    fun `an address already listed is refused by the name it is listed under`() {
        assertEquals(RepositoryInput.Duplicate("Maven Central"), parse("https://REPO1.maven.org/maven2/"))
        assertEquals(RepositoryInput.Duplicate("Google"), parse("dl.google.com/dl/android/maven2"))
    }

    @Test
    fun `the path of an address keeps its case`() {
        assertEquals(false, sameRepositoryUrl("https://example.com/Repo", "https://example.com/repo"))
        assertEquals(true, sameRepositoryUrl("HTTPS://Example.com/Repo/", "https://example.com/Repo"))
    }
}
