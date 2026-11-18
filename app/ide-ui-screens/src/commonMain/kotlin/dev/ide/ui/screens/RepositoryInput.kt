package dev.ide.ui.screens

import dev.ide.ui.backend.UiRepository

/** A well-known Maven repository offered as a one-tap add in the Repositories manager. */
internal data class SuggestedRepository(val name: String, val url: String)

/**
 * The repositories people most often have to add by hand. Names are proper nouns and stay untranslated.
 * Kept short on purpose: every entry is one more place a resolve looks, so only ones a project is likely to
 * need belong here.
 */
internal val SUGGESTED_REPOSITORIES = listOf(
    SuggestedRepository("JitPack", "https://jitpack.io"),
    SuggestedRepository("Gradle Plugin Portal", "https://plugins.gradle.org/m2"),
    SuggestedRepository("Sonatype Snapshots", "https://central.sonatype.com/repository/maven-snapshots"),
)

/** What typing into the Repositories manager's add fields came to. */
internal sealed interface RepositoryInput {
    /** A repository ready to add. [insecure] when it is plain http, which is allowed but worth a warning. */
    data class Ok(val name: String, val url: String, val insecure: Boolean) : RepositoryInput

    /** The text holds nothing that reads as a web address. */
    data object NotAnAddress : RepositoryInput

    /** The address is already in the list, as the repository named [name]. */
    data class Duplicate(val name: String) : RepositoryInput
}

/**
 * Turns what the user typed or pasted into a repository to add.
 *
 * Forgiving about the address, since it is usually copied from a README: a whole Gradle line
 * (`maven { url = uri("https://jitpack.io") }`) yields the address inside it, a bare host (`jitpack.io`)
 * gets `https://`, and a trailing slash is dropped. A blank [name] becomes the suggested repository's name
 * when the address is a known one, else the host.
 */
internal fun parseRepositoryInput(name: String, text: String, existing: List<UiRepository>): RepositoryInput {
    val url = extractRepositoryUrl(text) ?: return RepositoryInput.NotAnAddress
    existing.firstOrNull { sameRepositoryUrl(it.url, url) }?.let { return RepositoryInput.Duplicate(it.name) }
    val resolvedName = name.trim().ifEmpty {
        SUGGESTED_REPOSITORIES.firstOrNull { sameRepositoryUrl(it.url, url) }?.name ?: hostOf(url)
    }
    return RepositoryInput.Ok(resolvedName, url, insecure = url.startsWith("http://"))
}

/** True when two repository addresses are the same place: scheme and host case, and a trailing slash, aside. */
internal fun sameRepositoryUrl(a: String, b: String): Boolean = canonical(a) == canonical(b)

private fun canonical(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd < 0) return trimmed.lowercase()
    val pathStart = trimmed.indexOf('/', schemeEnd + 3).let { if (it < 0) trimmed.length else it }
    return trimmed.substring(0, pathStart).lowercase() + trimmed.substring(pathStart)
}

private val EMBEDDED_URL = Regex("""https?://[^\s"'()<>]+""", RegexOption.IGNORE_CASE)
private val BARE_HOST = Regex("""[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+(:\d+)?(/[^\s"'()<>]*)?""")

/** The repository address in [text], normalized, or null when there is none. */
internal fun extractRepositoryUrl(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val found = EMBEDDED_URL.find(trimmed)?.value
        ?: trimmed.takeIf { BARE_HOST.matches(it) }?.let { "https://$it" }
        ?: return null
    val url = found.trimEnd('/', '.', ',', ';')
    // A scheme with nothing after it ("https://") is not an address.
    return url.takeIf { hostOf(it).isNotEmpty() }
}

private fun hostOf(url: String): String =
    url.substringAfter("://", "").substringBefore('/').substringBefore(':')
