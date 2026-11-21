package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The construction-order checks (UNINITIALIZED_ENUM_COMPANION, INSTANCE_ACCESS_BEFORE_SUPER_CALL), the inline
 * body rules (USAGE_IS_NOT_INLINABLE, NON_PUBLIC_CALL_FROM_PUBLIC_INLINE) and the widened `when`
 * exhaustiveness (NO_ELSE_IN_WHEN): each error is reported where K2 reports it, and each look-alike the
 * compiler accepts stays clean.
 */
class KotlinConstructionAndInlineChecksTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun b(code: String) = "package demo\n$code\n"

    private fun lineOf(code: String, d: Diagnostic): Int = code.take(d.range.start).count { it == '\n' }

    private fun codes(code: String, file: String, wanted: String): List<Int> {
        val src = b(code)
        return diagnose(file, src).filter { it.code == wanted }.map { lineOf(src, it) }.sorted()
    }

    // --- UNINITIALIZED_ENUM_COMPANION ---

    @Test
    fun enumCompanionReadDuringEntryCreationIsFlagged() {
        val code = """
            enum class E(val x: Int) {
                A(foo()),
                B(Companion.foo()),
                C(E.foo()),
                D(E.Companion.foo()),
                F(K);
                val y = x + K
                companion object {
                    const val K = 1
                    fun foo() = 42
                }
            }
        """.trimIndent()
        assertEquals(listOf(2, 3, 4, 5, 6, 7), codes(code, "EC1.kt", "kt.uninitializedEnumCompanion"))
    }

    @Test
    fun enumCompanionReadLaterIsClean() {
        val code = """
            enum class E(val x: Int) {
                A(1) { override fun g() = foo() },
                B(2) { override fun g() = 0 };
                abstract fun g(): Int
                val lazyRead get() = foo()
                val deferred = { foo() }
                fun h() = Companion.foo() + E.foo()
                companion object {
                    fun foo() = 42
                    val all = values().map { it.x }
                }
            }
            enum class Other(val v: Int) { X(E.foo()) }
        """.trimIndent()
        assertEquals(emptyList(), codes(code, "EC2.kt", "kt.uninitializedEnumCompanion"))
    }

    @Test
    fun enumMemberShadowingTheCompanionIsClean() {
        val code = """
            enum class E {
                A;
                fun foo() = 1
                val y = foo()
                companion object { fun foo() = 2 }
            }
        """.trimIndent()
        assertEquals(emptyList(), codes(code, "EC3.kt", "kt.uninitializedEnumCompanion"))
    }

    // --- INSTANCE_ACCESS_BEFORE_SUPER_CALL ---

    @Test
    fun instanceUsedInConstructorHeaderIsFlagged() {
        val code = """
            open class Base(val v: Int)
            class A(x: Int = size()) : Base(prop) {
                constructor(s: String) : this(s.length + prop)
                constructor(b: Boolean, other: A = this) : this(1)
                val prop = 1
                fun size() = 2
            }
            object O : Base(field) { val field = 3 }
        """.trimIndent()
        assertEquals(listOf(2, 2, 3, 4, 8), codes(code, "IA1.kt", "kt.instanceAccessBeforeSuperCall"))
    }

    @Test
    fun constructorHeaderWithoutTheInstanceIsClean() {
        val code = """
            open class Base(val v: Int)
            class A(val prop: Int = CONST, n: Nested = Nested()) : Base(prop) {
                constructor(prop: String) : this(prop.length)
                constructor(b: Boolean) : this(make())
                class Nested
                val other = prop
                companion object {
                    const val CONST = 1
                    fun make() = 2
                }
            }
        """.trimIndent()
        assertEquals(emptyList(), codes(code, "IA2.kt", "kt.instanceAccessBeforeSuperCall"))
    }

    // --- USAGE_IS_NOT_INLINABLE ---

    @Test
    fun inlineParameterUsedAsValueIsFlagged() {
        val code = """
            fun store(f: () -> Unit) {}
            inline fun g(block: () -> Unit) {
                val x = block
                store(block)
                block ?: block
                block.toString()
            }
        """.trimIndent()
        assertEquals(listOf(3, 4, 5, 5, 6), codes(code, "UI1.kt", "kt.usageIsNotInlinable"))
    }

    @Test
    fun inlineParameterCalledOrPassedOnIsClean() {
        val code = """
            inline fun other(f: () -> Unit) = f()
            fun store(f: () -> Unit) {}
            inline fun g(block: () -> Unit, noinline later: () -> Unit) {
                block()
                block.invoke()
                other(block)
                store(later)
                val copy = later
                listOf(1).forEach { block() }
            }
            inline fun h(block: () -> Unit) { val block = 1; println(block) }
        """.trimIndent()
        assertEquals(emptyList(), codes(code, "UI2.kt", "kt.usageIsNotInlinable"))
    }

    // --- NON_PUBLIC_CALL_FROM_PUBLIC_INLINE ---

    @Test
    fun publicInlineTouchingPrivateApiIsFlagged() {
        val code = """
            private fun hidden() = 1
            internal val counter = 2
            inline fun g() {
                hidden()
                println(counter)
            }
            class C {
                private val secret = 3
                inline fun h() = secret
            }
        """.trimIndent()
        assertEquals(listOf(4, 5, 9), codes(code, "NP1.kt", "kt.nonPublicCallFromPublicInline"))
    }

    @Test
    fun nonPublicInlineOrPublishedApiIsClean() {
        val code = """
            private fun hidden() = 1
            @PublishedApi internal fun published() = 2
            internal inline fun g() = hidden()
            private inline fun p() = hidden()
            inline fun q() = published()
            fun plain() = hidden()
            class C {
                private val secret = 3
                internal inline fun h() = secret
            }
        """.trimIndent()
        assertEquals(emptyList(), codes(code, "NP2.kt", "kt.nonPublicCallFromPublicInline"))
    }

    // --- NO_ELSE_IN_WHEN ---

    @Test
    fun nonExhaustiveWhenIsFlagged() {
        val code = """
            enum class E { A, B }
            sealed class S { object X : S(); object Y : S() }
            fun f(e: E, s: S, b: Boolean, n: Int) {
                when (e) { E.A -> println() }
                when (b) { true -> println() }
                val x = when { n > 0 -> 1 }
                val y = when (n) { 1 -> "one" }
                val z = when (s) { S.X -> 1 }
            }
        """.trimIndent()
        assertEquals(listOf(4, 5, 6, 7, 8), codes(code, "WE1.kt", "kt.whenExhaustive"))
    }

    @Test
    fun exhaustiveOrStatementWhenIsClean() {
        val code = """
            enum class E { A, B }
            sealed class S { object X : S(); object Y : S() }
            fun f(e: E, s: S, b: Boolean, n: Int, t: String) {
                when (e) { E.A -> println(); E.B -> println() }
                when (b) { true -> println(); false -> println() }
                when { n > 0 -> println() }
                when (n) { 1 -> println() }
                when (t) { "a" -> println() }
                val x = when (s) { S.X -> 1; S.Y -> 2 }
                val y = when (n) { 1 -> "one"; else -> "many" }
                if (s != S.X) return
                when (s) { S.Y -> println() }
            }
        """.trimIndent()
        assertEquals(emptyList(), codes(code, "WE2.kt", "kt.whenExhaustive"))
    }

    // Borrowed from KotlinStatementChecksTest rather than built here: every jvmTest class shares one JVM, whose
    // heap runs close to its limit, and an analyzer held in a companion stays alive for the rest of the run.
    private val srcDir: Path get() = KotlinStatementChecksTest.srcDir
    private val analyzer get() = KotlinStatementChecksTest.analyzer
}
