package dev.ide.lang.kotlin

import dev.ide.index.ClassNameValue
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.MemberValue
import dev.ide.index.SymbolValue
import dev.ide.lang.kotlin.index.KotlinClassNamesIndex
import dev.ide.lang.kotlin.index.KotlinMembersIndex
import dev.ide.lang.kotlin.index.KotlinPackageTypesIndex
import dev.ide.lang.kotlin.index.KotlinPackagesIndex
import dev.ide.lang.kotlin.index.KotlinSourceSymbolsIndex
import dev.ide.platform.ContentHash
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `kotlin.*` workspace indexes (class names / go-to-symbol / package contents / package names / members)
 * produced from a resolution-free PSI parse — the Kotlin-source siblings of the `java.*` producers, replacing
 * the old (wrong) path where a `.kt` file was fed to the Java parser. Verifies each producer emits the shared
 * value shapes with Kotlin-correct kinds (`object`, extension-less members, nested FQNs).
 */
class KotlinWorkspaceIndexesTest {

    private val src = """
        package com.example

        class Greeter(val name: String) {
            fun greet(): String = "hi ${'$'}name"
            val length: Int get() = name.length
            interface Listener
        }

        interface Api
        enum class Color { RED, GREEN }
        annotation class Marker
        object Registry { fun clear() {} }
        fun topLevel() {}
    """.trimIndent()

    private fun input(fileId: Int = 7) = KtInput("Greeter.kt", src, fileId)

    @Test
    fun classNamesIndexesEveryClassifierWithItsKotlinKind() {
        val out = KotlinClassNamesIndex.index(input())
        assertEquals("com.example.Greeter", out["Greeter"]?.first()?.fqn)
        assertEquals("class", out["Greeter"]?.first()?.kind)
        assertEquals(IndexOrigin.SOURCE, out["Greeter"]?.first()?.origin)
        assertEquals("interface", out["Api"]?.first()?.kind)
        assertEquals("enum", out["Color"]?.first()?.kind)
        assertEquals("annotation", out["Marker"]?.first()?.kind)
        assertEquals("object", out["Registry"]?.first()?.kind, "a Kotlin object keeps its own kind")
        // Nested types carry their true (nested-aware) FQN, keyed by simple name.
        assertEquals("com.example.Greeter.Listener", out["Listener"]?.first()?.fqn)
        // An enum CONSTANT is a value of the enum type, NOT a classifier — indexing it as a source class made
        // `isKnownType("Color.RED")` true, so a `Color.RED` use mis-resolved to a classifier and drew a spurious
        // "does not have a companion object, and thus must be initialized here" error.
        assertTrue("RED" !in out.keys, "an enum constant is not a class name; got ${out.keys}")
        assertTrue("GREEN" !in out.keys, "an enum constant is not a class name; got ${out.keys}")
    }

    @Test
    fun packageTypesListsOnlyTheTopLevelTypesOfThePackage() {
        val out = KotlinPackageTypesIndex.index(input())
        val fqns = out["com.example"].orEmpty().map(ClassNameValue::fqn).toSet()
        assertTrue("com.example.Greeter" in fqns, "top-level type present; got $fqns")
        assertTrue("com.example.Registry" in fqns, "an object is a top-level type too; got $fqns")
        assertTrue("com.example.Greeter.Listener" !in fqns, "nested types are not direct package members; got $fqns")
    }

    @Test
    fun packagesIndexesEveryPrefixOfThePackage() {
        val out = KotlinPackagesIndex.index(input())
        assertTrue("com" in out.keys, "got ${out.keys}")
        assertTrue("com.example" in out.keys, "got ${out.keys}")
    }

    @Test
    fun sourceSymbolsCarryFileKindOffsetAndContainer() {
        val out = KotlinSourceSymbolsIndex.index(input(fileId = 7))
        assertTrue(setOf("Greeter", "greet", "length", "topLevel", "Registry").all { it in out.keys },
            "decls indexed; got ${out.keys}")
        val greet = out["greet"]?.first()
        assertNotNull(greet)
        assertEquals("method", greet.kind)
        assertEquals(7, greet.fileId)
        assertEquals("Greeter", greet.container, "a method's container is its enclosing class")
        assertTrue(greet.offset > 0, "offset points at the name identifier")
        assertEquals("field", out["length"]?.first()?.kind, "a property indexes as a field")
        assertEquals(null, out["topLevel"]?.first()?.container, "a top-level function has no container")
        assertEquals("object", out["Registry"]?.first()?.kind)
    }

    @Test
    fun sourceSymbolsEmptyWithoutAFileId() {
        assertTrue(KotlinSourceSymbolsIndex.index(KtInput("Greeter.kt", src, fileId = -1)).isEmpty(),
            "no interned file id → nothing navigable to emit")
    }

    @Test
    fun membersIndexesFunctionsAndPropertiesByOwner() {
        val out = KotlinMembersIndex.index(input())
        assertEquals("Greeter", out["greet"]?.first()?.owner)
        assertEquals("method", out["greet"]?.first()?.kind)
        assertEquals("field", out["length"]?.first()?.kind)
        assertEquals("Registry", out["clear"]?.first()?.owner, "member of a nested object")
        assertEquals("", out["topLevel"]?.first()?.owner, "a top-level function has no owning class")
    }

    private class KtInput(
        override val unitName: String,
        private val body: String,
        override val fileId: Int,
    ) : IndexInput {
        override val origin = IndexOrigin.SOURCE
        override val contentHash = ContentHash("")
        override val sourcePath: Path = Paths.get("/virtual/$unitName")
        override fun bytes() = body.toByteArray()
        override fun text() = body
        override fun dom() = null
    }
}
