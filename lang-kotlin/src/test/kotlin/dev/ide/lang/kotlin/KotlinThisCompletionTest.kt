package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `this.` member completion across the receiver kinds Kotlin allows `this` to denote: the enclosing class,
 * an extension function's receiver, a *concrete*-receiver lambda (the Compose `Column { this.<caret> }`
 * scope pattern — `Scope.() -> Unit`), and a scope function (`apply`/`run`) whose generic `T` receiver is
 * bound from the call receiver. The receiver type members (and inherited ones) must be offered.
 *
 * All fixtures use SOURCE-declared types so resolution doesn't depend on the classpath index being ready
 * (a classpath type like `StringBuilder` would be gated by dumb mode in this lightweight harness).
 */
class KotlinThisCompletionTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    @Test
    fun thisInClassMemberSeesOwnAndInherited() {
        // `this` inside Child.m() → Child's own + Parent's inherited members.
        val items = runBlocking {
            analyzer.completeAtCaret(srcDir, "Child.kt", CHILD.replace("/*caret*/", "this.|"))
        }.items.map { it.symbol?.name ?: it.label }
        assertTrue("childField" in items, "own member via this; got ${items.take(20)}")
        assertTrue("parentMethod" in items, "inherited member via this; got ${items.take(20)}")
    }

    @Test
    fun thisInExtensionReceiver() {
        val ls = labels("package demo\nfun Holder.ext() { this.| }")
        assertTrue("held" in ls, "extension receiver member via this; got ${ls.take(20)}")
    }

    @Test
    fun thisInConcreteReceiverLambda() {
        // `myScope { this.<caret> }` — content: RowScope.() -> Unit. The Compose `Column { }` shape.
        val ls = labels("package demo\nfun g() { myScope { this.| } }")
        assertTrue("weight" in ls, "scope member via this in a receiver lambda; got ${ls.take(20)}")
    }

    @Test
    fun thisInScopeFunctionOnSourceType() {
        // `Holder().apply { this.<caret> }` — apply's generic `T` receiver bound from the call receiver.
        val ls = labels("package demo\nfun g() { Holder().apply { this.| } }")
        assertTrue("held" in ls, "receiver member via this in apply{}; got ${ls.take(20)}")
    }

    @Test
    fun bareNameInScopeSeesImplicitReceiver() {
        // No `this.` — the scope's members are in scope implicitly.
        assertTrue("weight" in labels("package demo\nfun g() { myScope { wei| } }"), "implicit-receiver member")
    }

    companion object {
        private const val CHILD =
            "package demo\nclass Child : Parent() {\n  val childField = 1\n  fun m() { /*caret*/ }\n}"

        val srcDir: Path = tempProject(
            mapOf(
                "Types.kt" to """
                    package demo
                    open class Parent { fun parentMethod(): String = "x" }
                    class Holder { val held = 1 ; fun take() {} }
                    class RowScope { fun weight(n: Int) {} ; val gravity = 0 }
                    fun myScope(content: RowScope.() -> Unit) {}
                """.trimIndent(),
                "Child.kt" to CHILD.replace("/*caret*/", ""),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
