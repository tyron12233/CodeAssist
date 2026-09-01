package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An `override` stub — from completion or from the "Implement members" quick-fix, which share
 * [dev.ide.lang.kotlin.completion.KotlinCompletionItems.overrideStubText] — must repeat every part of the
 * overridden signature that Kotlin requires, or the generated code does not compile. Reported for `suspend`
 * ("Non-suspend function 'load' cannot override suspend function"); the same header dropped the
 * type-parameter list (leaving `T` unresolved) and `vararg` (so the stub overrode nothing).
 *
 * `KotlinOverrideStubCompilesTest` pins the same stubs against the real compiler; this pins the exact text.
 */
class KotlinOverrideStubSignatureTest {

    private fun headers(): List<String> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, listOf(stdlibJarPath())))
        val res = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "package demo\nimport util.Repo\nclass R : Repo { |}")
        }
        return res.items.filter { it.detail == "override" }.map { it.label }
    }

    @Test
    fun suspendIsCarriedIntoTheStub() {
        val h = headers()
        assertTrue(
            "override suspend fun load(id: String): String" in h,
            "a suspend member's override must stay suspend; got $h",
        )
        assertTrue("override suspend fun flush()" in h, "…including a Unit-returning one; got $h")
    }

    @Test
    fun aPlainMemberIsUnchanged() {
        val h = headers()
        assertTrue("override fun plain(): Int" in h, "a non-suspend member must not gain modifiers; got $h")
        assertTrue("override val name: String" in h, "a property stub is unchanged; got $h")
    }

    @Test
    fun theTypeParameterListIsCarriedWithItsBounds() {
        val h = headers()
        assertTrue(
            "override fun <T> transform(t: T): T" in h,
            "without `<T>` the stub's `T` is unresolved; got $h",
        )
        assertTrue(
            "override fun <T : Number> clamp(t: T): T" in h,
            "a DIFFERENT bound (or none) overrides nothing, so the bound comes along; got $h",
        )
    }

    @Test
    fun varargIsCarriedOnTheRightParameter() {
        val h = headers()
        assertTrue("override fun sum(vararg xs: Int): Int" in h, "a dropped `vararg` overrides nothing; got $h")
        assertTrue(
            "override fun pack(name: String, vararg parts: String): String" in h,
            "the keyword must land on the vararg parameter, not the first one; got $h",
        )
    }

    /** A comma inside a generic argument list / function type is not a parameter separator. */
    @Test
    fun nestedCommasDoNotConfuseTheParameterSplit() {
        assertTrue(
            "override fun mix(m: Map<String, Int>, f: (Int, Int) -> Unit)" in headers(),
            "the parameter list was mangled; got ${headers()}",
        )
    }

    /** `@Composable` is part of a function's type, so an override repeats it. */
    @Test
    fun composableIsCarriedIntoTheStub() {
        assertTrue(
            headers().any { it == "@Composable override fun Content()" },
            "a @Composable member's override must stay @Composable; got ${headers()}",
        )
    }

    /** `operator`/`infix` are inherited by an override — repeating them is optional, so the stub stays clean. */
    @Test
    fun operatorAndInfixAreNotRepeated() {
        val h = headers()
        assertTrue("override fun get(i: Int): String" in h, "operator is inherited; got $h")
        assertTrue("override fun mixIn(o: Repo): Repo" in h, "infix is inherited; got $h")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Api.kt" to """
                package util
                annotation class Composable
                interface Repo {
                    suspend fun load(id: String): String
                    suspend fun flush()
                    fun plain(): Int
                    val name: String
                    fun <T> transform(t: T): T
                    fun <T : Number> clamp(t: T): T
                    fun sum(vararg xs: Int): Int
                    fun pack(name: String, vararg parts: String): String
                    fun mix(m: Map<String, Int>, f: (Int, Int) -> Unit)
                    @Composable fun Content()
                    operator fun get(i: Int): String
                    infix fun mixIn(o: Repo): Repo
                }
                """.trimIndent(),
            ),
        )
    }
}
