package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct inheritors with NO index at all — the module's own source model answering for its own types.
 *
 * [KotlinInheritorsQueryTest] covers the index side of the same query. This covers the half a host may have
 * to live without: the iOS host indexes the CLASSPATH and nothing else, because the workspace indexer is
 * 1,400 lines of `java.nio.file` / `java.lang.module` / `java.util.concurrent` that does not port. Every
 * project type's gutter "implementations" marker was therefore blank there, and go-to-implementation found
 * nothing, although the symbol model knew the whole hierarchy all along.
 *
 * It is also the FRESHER answer everywhere else: the model tracks the live buffer, so a supertype added a
 * keystroke ago is here and is in no segment yet.
 */
class KotlinSourceInheritorsTest {

    private fun serviceOver(vararg files: Pair<String, String>): KotlinSymbolService {
        val dir = tempProject(files.toMap())
        return KotlinSymbolService(
            sourceRoots = listOf(DiskFile(dir)),
            classpathJars = emptyList(),
            index = null,
        )
    }

    @Test
    fun everySubclassOfASealedHierarchyIsFoundWithoutAnIndex() {
        val svc = serviceOver(
            "Expr.kt" to """
            package demo
            sealed class Expr
            class Add : Expr()
            class Neg : Expr()
            object Zero : Expr()
            """.trimIndent(),
        )

        assertEquals(
            setOf("demo.Add", "demo.Neg", "demo.Zero"),
            svc.directInheritors("demo.Expr").map { it.fqn }.toSet(),
        )
    }

    @Test
    fun theSubtypeKindIsCarriedSoAMarkerCanRenderIt() {
        val svc = serviceOver(
            "Kinds.kt" to """
            package demo
            interface Shape
            class Square : Shape
            object Nothing2 : Shape
            enum class Corner : Shape { A }
            """.trimIndent(),
        )

        val byFqn = svc.directInheritors("demo.Shape").associate { it.fqn to it.kind }
        assertEquals("class", byFqn["demo.Square"], "got $byFqn")
        assertEquals("object", byFqn["demo.Nothing2"], "got $byFqn")
        assertEquals("enum", byFqn["demo.Corner"], "got $byFqn")
    }

    @Test
    fun inheritorsAreFoundAcrossFiles() {
        val svc = serviceOver(
            "Base.kt" to "package demo\nabstract class Base\n",
            "Impl.kt" to "package demo\nclass Impl : Base()\n",
        )

        assertEquals(listOf("demo.Impl"), svc.directInheritors("demo.Base").map { it.fqn })
    }

    /** The supertype is RESOLVED, so a same-short-name type in another package is not a match. */
    @Test
    fun aHomonymInAnotherPackageIsNotAnInheritor() {
        val svc = serviceOver(
            "Impl.kt" to "package demo\nimport other.Base\nclass Impl : Base()\n",
            "Base.kt" to "package other\nabstract class Base\n",
            "Decoy.kt" to "package demo\nabstract class Base\n",
        )

        assertTrue(svc.directInheritors("other.Base").any { it.fqn == "demo.Impl" }, "the imported one matches")
        assertTrue(
            svc.directInheritors("demo.Base").none { it.fqn == "demo.Impl" },
            "`demo.Base` shares the short name and is not what `Impl` extends",
        )
    }

    /** Transitive closure over the same source-derived relation. */
    @Test
    fun theClosureWalksTransitivelyWithoutAnIndex() {
        val svc = serviceOver(
            "Expr.kt" to """
            package demo
            sealed class Expr
            sealed class Bin : Expr()
            class BinAdd : Bin()
            class Lit : Expr()
            """.trimIndent(),
        )

        assertEquals(setOf("demo.BinAdd"), svc.directInheritors("demo.Bin").map { it.fqn }.toSet())
        assertEquals(
            setOf("demo.Bin", "demo.BinAdd", "demo.Lit"),
            svc.allInheritors("demo.Expr").map { it.fqn }.toSet(),
        )
    }

    /** A local/anonymous type has a synthetic FQN that nothing can navigate to, so it is not offered. */
    @Test
    fun aLocalSubclassIsNotOfferedAsAnInheritor() {
        val svc = serviceOver(
            "Local.kt" to """
            package demo
            abstract class Base
            fun make(): Base {
                class Hidden : Base()
                return Hidden()
            }
            """.trimIndent(),
        )

        assertEquals(
            emptyList(),
            svc.directInheritors("demo.Base").map { it.fqn },
            "a type declared inside a function body is not navigable",
        )
    }

    /** A final class cannot be extended, so nothing should claim it as a supertype. */
    @Test
    fun aTypeWithNoSubclassesReportsNone() {
        val svc = serviceOver("Alone.kt" to "package demo\nclass Alone\nclass Other\n")

        assertEquals(emptyList(), svc.directInheritors("demo.Alone").map { it.fqn })
    }
}
