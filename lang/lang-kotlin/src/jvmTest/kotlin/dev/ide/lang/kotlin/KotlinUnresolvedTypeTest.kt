package dev.ide.lang.kotlin

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.dom.Diagnostic
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The unresolved-TYPE diagnostic: a type used by simple name that EXISTS on the classpath (it's in the
 * `java.classNames` index) but was never imported must be flagged `kt.unresolved` — the missing-import case
 * (`class X : ComponentActivity()` with no `import androidx.activity.ComponentActivity`). Resolution no longer
 * silently resolves such a name through the global class-name index. Conservative counterparts (imported,
 * default-imported, same-package, generic type parameters) must NOT be flagged.
 *
 * A fake [IndexService] stands in for the real one (built in ide-core), serving only the simple-name → FQN
 * mapping the diagnostic needs to know the name is a real (but unimported) classpath type.
 */
class KotlinUnresolvedTypeTest {

    private val componentActivityFqn = "androidx.activity.ComponentActivity"

    @Suppress("UNCHECKED_CAST")
    private val fakeIndex = object : IndexService {
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = when {
            // A LIBRARY type: needs an import → must be flagged when unimported.
            id.value == "java.classNames" && key == "ComponentActivity" ->
                sequenceOf(ClassNameValue(componentActivityFqn, IndexOrigin.LIBRARY, "class")) as Sequence<V>
            // A same-module Java SOURCE type: needs no import in the same package → must resolve.
            id.value == "java.classNames" && key == "LocalJavaType" ->
                sequenceOf(ClassNameValue("com.example.LocalJavaType", IndexOrigin.SOURCE, "class")) as Sequence<V>
            // A same-module Java SOURCE type in a DIFFERENT package: needs an import → must be flagged unimported.
            id.value == "java.classNames" && key == "OtherJavaType" ->
                sequenceOf(ClassNameValue("com.other.OtherJavaType", IndexOrigin.SOURCE, "class")) as Sequence<V>
            else -> emptySequence()
        }

        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus(ready = true)
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }

    private fun diagnose(fileName: String, code: String, seed: Map<String, String> = emptyMap()): List<Diagnostic> {
        val srcDir = tempProject(seed) // seeded .kt files land in the source model (`classByFqn`)
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).apply { indexService = fakeIndex }
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun unimportedSupertypeIsUnresolved() {
        val diags = diagnose(
            "MainActivity.kt",
            "package com.example\nclass MainActivity : ComponentActivity() {}",
        )
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("ComponentActivity") },
            "an unimported classpath supertype must be flagged unresolved; got $diags",
        )
    }

    @Test
    fun unimportedTypeReferenceIsUnresolved() {
        val diags = diagnose(
            "Use.kt",
            "package com.example\nfun f(a: ComponentActivity) {}",
        )
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("ComponentActivity") },
            "an unimported classpath type reference must be flagged unresolved; got $diags",
        )
    }

    @Test
    fun importedTypeReferenceResolves() {
        val diags = diagnose(
            "Use.kt",
            "package com.example\nimport androidx.activity.ComponentActivity\nfun f(a: ComponentActivity) {}",
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" },
            "an explicitly-imported type must not be flagged; got $diags",
        )
    }

    @Test
    fun unknownTypeNameIsFlagged() {
        // A type name that resolves to nothing in scope is flagged — even one that isn't on the classpath at
        // all (a typo / a not-yet-created class). `val a: AnyClass` must be marked unresolved.
        val diags = diagnose(
            "Use.kt",
            "package com.example\nfun f(a: TotallyUnknownXyz) {}",
        )
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("TotallyUnknownXyz") },
            "an unresolved type name must be flagged; got $diags",
        )
    }

    @Test
    fun sameModuleJavaSourceTypeResolves() {
        // A same-package project Java SOURCE class (SOURCE origin in the index) needs no import — must resolve.
        val diags = diagnose("Use.kt", "package com.example\nfun f(a: LocalJavaType) {}")
        assertTrue(
            diags.none { it.code == "kt.unresolved" && it.message.contains("LocalJavaType") },
            "a same-module Java source type must not be flagged; got $diags",
        )
    }

    @Test
    fun crossPackageProjectSourceTypeIsUnresolved() {
        // A project Kotlin SOURCE type in a DIFFERENT package, used without an import, must be flagged: Kotlin
        // requires the import even for a same-module type (only a SAME-package type resolves bare). The bug this
        // guards was the `NoteRepository`/`NoteDao` case where a `data`-package DAO in another package went unflagged.
        val diags = diagnose(
            "Use.kt",
            "package com.example\nfun f(a: OtherPkgType) {}",
            seed = mapOf("OtherPkgType.kt" to "package com.other\nclass OtherPkgType"),
        )
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("OtherPkgType") },
            "an unimported different-package project type must be flagged unresolved; got $diags",
        )
    }

    @Test
    fun crossPackageProjectSourceConstructorCallIsUnresolved() {
        // The same rule via the bare-reference path: `OtherPkgType()` (a different-package project type, no import).
        val diags = diagnose(
            "Use.kt",
            "package com.example\nfun f() { val x = OtherPkgType() }",
            seed = mapOf("OtherPkgType.kt" to "package com.other\nclass OtherPkgType"),
        )
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("OtherPkgType") },
            "an unimported different-package constructor call must be flagged; got $diags",
        )
    }

    @Test
    fun crossPackageJavaSourceTypeIsUnresolved() {
        // A project JAVA SOURCE type (index SOURCE origin) in a DIFFERENT package, used without an import, is flagged.
        val diags = diagnose("Use.kt", "package com.example\nfun f(a: OtherJavaType) {}")
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("OtherJavaType") },
            "an unimported different-package Java source type must be flagged; got $diags",
        )
    }

    @Test
    fun samePackageProjectSourceTypeResolves() {
        // The counterpart: a project type in the SAME package needs no import — must NOT be flagged, and an
        // imported cross-package one must resolve too.
        val same = diagnose(
            "Use.kt",
            "package com.example\nfun f(a: SamePkgType) {}",
            seed = mapOf("SamePkgType.kt" to "package com.example\nclass SamePkgType"),
        )
        assertTrue(
            same.none { it.code == "kt.unresolved" && it.message.contains("SamePkgType") },
            "a same-package project type must not be flagged; got $same",
        )
        val imported = diagnose(
            "Use.kt",
            "package com.example\nimport com.other.OtherPkgType\nfun f(a: OtherPkgType) {}",
            seed = mapOf("OtherPkgType.kt" to "package com.other\nclass OtherPkgType"),
        )
        assertTrue(
            imported.none { it.code == "kt.unresolved" && it.message.contains("OtherPkgType") },
            "an explicitly-imported cross-package project type must resolve; got $imported",
        )
    }

    @Test
    fun projectTypeAliasIsNotFlagged() {
        // A `typealias` is not a class (the source model doesn't resolve it) — a reference to one must NOT be
        // flagged, whether declared in the same file or elsewhere in the module.
        val diags = diagnose(
            "Use.kt",
            "package com.example\ntypealias Handler = (Int) -> Unit\nfun f(h: Handler) {}",
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && it.message.contains("Handler") },
            "a typealias reference must not be flagged; got $diags",
        )
    }

    @Test
    fun genericTypeParameterIsNotFlagged() {
        val diags = diagnose(
            "Use.kt",
            "package com.example\nfun <T> f(a: T): T = a",
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" },
            "a generic type parameter must not be flagged; got $diags",
        )
    }

    @Test
    fun unimportedBareExpressionReferenceIsFlagged() {
        // A capitalized name used as a bare expression (not a call / not a receiver) that resolves to nothing
        // — `fun main() { ComponentActivity }` — must be flagged unresolved too.
        val diags = diagnose("Use.kt", "package com.example\nfun f() { ComponentActivity }")
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("ComponentActivity") },
            "an unimported bare capitalized reference must be flagged; got $diags",
        )
    }

    @Test
    fun unresolvedConstructorCallIsFlagged() {
        val diags = diagnose("Use.kt", "package com.example\nfun f() { val x = BogusType() }")
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("BogusType") },
            "an unresolved constructor call must be flagged; got $diags",
        )
    }

    @Test
    fun sameFileObjectAndCallReferencesResolve() {
        // A same-file `object` and a same-file (capitalized, composable-style) function — both resolve bare
        // from the live buffer, even before the disk index catches up.
        val diags = diagnose(
            "Use.kt",
            "package com.example\nobject Settings\nfun Greeting() {}\nfun f() { val s = Settings\n  Greeting() }",
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" },
            "a same-file object / capitalized function must not be flagged; got $diags",
        )
    }

    @Test
    fun builtinTypeIsNotFlagged() {
        val diags = diagnose("Use.kt", "package com.example\nfun f(a: Int, b: String): Boolean = true")
        assertTrue(
            diags.none { it.code == "kt.unresolved" },
            "default simple types (Int/String/Boolean) must not be flagged; got $diags",
        )
    }

    @Test
    fun unimportedLibraryTypeAsStaticReceiverIsFlagged() {
        // `FontWeight.Bold` shape: a LIBRARY type used for companion/static access, never imported. The receiver
        // itself must be flagged (Kotlin reports "Unresolved reference: FontWeight") — the selector can't be
        // typed, so the member check backs off and the receiver used to fall through every check silently.
        val diags = diagnose("Use.kt", "package com.example\nfun f() { val x = ComponentActivity.INSTANCE }")
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("ComponentActivity") },
            "an unimported library type used as a static/companion receiver must be flagged; got $diags",
        )
    }

    @Test
    fun sameModuleSourceTypeAsStaticReceiverIsNotFlagged() {
        // A same-package SOURCE type as receiver resolves without an import — must NOT be flagged.
        val diags = diagnose("Use.kt", "package com.example\nfun f() { val x = LocalJavaType.FIELD }")
        assertTrue(
            diags.none { it.code == "kt.unresolved" && it.message.contains("LocalJavaType") },
            "a same-module source type receiver must not be flagged; got $diags",
        )
    }

    @Test
    fun generatedOrUnknownReceiverIsNotFlagged() {
        // A capitalized receiver that is NOT a known library type — an Android `R`/`BuildConfig` not yet built,
        // or a typo — must NOT be flagged: we can't tell a missing import from a generated same-package class.
        val diags = diagnose("Use.kt", "package com.example\nfun f() { val x = R.drawable.icon }")
        assertTrue(
            diags.none { it.code == "kt.unresolved" && it.message.contains("R") },
            "an unknown/generated receiver (Android R) must not be flagged; got $diags",
        )
    }

    @Test
    fun packageSegmentReceiverIsNotFlagged() {
        // The package head of a fully-qualified reference (`unknownpkg.Foo.bar`) must not be flagged unresolved.
        val diags = diagnose("Use.kt", "package com.example\nfun f() { val x = unknownpkg.Foo.bar }")
        assertTrue(
            diags.none { it.code == "kt.unresolved" && it.message.contains("unknownpkg") },
            "a package-segment receiver must not be flagged; got $diags",
        )
    }

    @Test
    fun nestedClassTypeReferencedBySimpleNameInEnclosingClassIsNotFlagged() {
        // A member/return typed as a PRIVATE NESTED class referenced by SIMPLE name from within the enclosing
        // class body (`private var pending: Plan?`, `fun make(): Plan`) must resolve — not be flagged unresolved.
        // (Regression for the 2048 app: `Plan` marked "Unresolved reference" in the editor while the preview ran.)
        val code = """
            package com.example
            class Game {
                private var pending: Plan? = null
                private class Plan(val n: Int)
                fun make(): Plan = Plan(1)
            }
        """.trimIndent()
        // Seed the same file into the source model (as a saved file would be), so the nested `Game.Plan` FQN is
        // known — the editor resolves the simple `Plan` against the enclosing class.
        val diags = diagnose("Game.kt", code, seed = mapOf("Game.kt" to code))
        assertTrue(
            diags.none { it.code == "kt.unresolved" && it.message.contains("Plan") },
            "a nested class referenced by simple name in its enclosing class must not be flagged unresolved; got $diags",
        )
    }
}
