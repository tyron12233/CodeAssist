package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The diagnostic/inference member probe ([KotlinSymbolService.membersNamed]) must push the member NAME into
 * the lookup so it materializes only same-named members + extensions — not the receiver's entire extension set
 * (the `kotlin.Any` bucket alone is thousands on a real classpath). Guards against a regression back to the
 * old `membersOf(…, null)` enumerate-then-filter, which was the per-keystroke explosion on Compose files.
 */
class KotlinMemberLookupPushdownTest {

    // stdlib is auto-bundled by the service, so a String already carries a large member+extension set.
    private val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = emptyList())

    @Test
    fun membersNamedReturnsOnlyTheRequestedNameAndFarFewerThanTheFullSet() {
        val all = service.membersOf("kotlin.String", emptyList(), null)
        val named = service.membersNamed("kotlin.String", emptyList(), "length")
        assertTrue(named.isNotEmpty() && named.all { it.name == "length" }, "must return only 'length'; got ${named.map { it.name }}")
        assertTrue(named.size < all.size / 5, "pushdown must materialize far fewer than the full set (named=${named.size}, all=${all.size})")
    }

    @Test
    fun membersNamedStillFindsAStdlibExtension() {
        // `uppercase` is a stdlib EXTENSION on String — the pushdown must still surface it (so a valid call
        // isn't falsely flagged unresolved), just without materializing every other String extension.
        val ext = service.membersNamed("kotlin.String", emptyList(), "uppercase")
        assertTrue(ext.any { it.name == "uppercase" }, "stdlib extension 'uppercase' must resolve via the name-pushed lookup")
    }

    @Test
    fun membersNamedReturnsNothingForAGenuinelyMissingMember() {
        assertTrue(service.membersNamed("kotlin.String", emptyList(), "definitelyNotARealMember").isEmpty())
    }
}
