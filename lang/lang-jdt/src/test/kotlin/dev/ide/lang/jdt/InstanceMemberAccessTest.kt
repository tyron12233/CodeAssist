package dev.ide.lang.jdt

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Member-access QOL on an instance/value qualifier (`list.`) vs a type/static qualifier (`Stream.`):
 *  - nested types are referenced through the type, never an instance → not offered on an instance;
 *  - a static member reached via an instance is legal but poor style → ranked below instance members.
 * A type qualifier keeps both (statics are the point; nested types like `Stream.Builder` are reachable).
 */
class InstanceMemberAccessTest {

    private fun labels(code: String): List<String> {
        val (analyzer, dir) = workspaceWith()
        return try {
            completeLabels(analyzer, dir.resolve("app/T.java"), code)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun instanceAccessHidesNestedTypes() {
        // Stream has a public nested type Builder; it must not appear on an instance member-select.
        val labels = labels("package app; class T { void m() { java.util.List.of(1).stream().|CARET| } }")
        assertTrue("map" in labels, "instance methods should still be present: $labels")
        assertFalse("Builder" in labels, "nested type Stream.Builder must not be offered on an instance: $labels")
    }

    @Test
    fun instanceAccessRanksInstanceMethodsAboveStaticOnes() {
        // `map`/`filter` are instance; `of`/`empty`/`generate` are static — instance must rank higher.
        val labels = labels("package app; class T { void m() { java.util.List.of(1).stream().|CARET| } }")
        val firstInstance = labels.indexOf("map")
        val firstStatic = labels.indexOf("of")
        assertTrue(firstInstance in 0 until firstStatic, "instance 'map' ($firstInstance) should rank above static 'of' ($firstStatic): $labels")
    }

    @Test
    fun typeAccessKeepsNestedTypesAndStatics() {
        val labels = labels("package app; class T { void m() { java.util.stream.Stream.|CARET| } }")
        assertTrue("of" in labels, "static methods expected on a type qualifier: $labels")
        assertTrue("Builder" in labels, "nested type Stream.Builder expected on a type qualifier: $labels")
    }
}
