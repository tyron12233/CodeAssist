package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `by`-delegated property (`var winner by mutableStateOf<Player?>(null)`) types as its delegate's `value`
 * member — the State/Lazy convention. Two independent defects used to break this for a CLASS MEMBER seen from
 * another file (the reported Compose shape):
 *  - member / cross-file delegated properties were never typed at all (they fell through to no-type, so
 *    `game.winner` had no usable type — "returns a function type, with no type");
 *  - an explicit type argument on a plain call (`mutableStateOf<List<Int>?>(null)`) was dropped, so the
 *    generic element was lost (`winningLine?.toSet()` came out `Set<T>` instead of `Set<Int>`).
 * Asserted the editor way: a member completes only when the receiver is typed correctly. The `Box`/`boxOf`
 * delegate infra is defined in SOURCE (`mutableStateOf` itself is a library, but the resolution path for a
 * source class member delegate is identical, and source keeps the test off the analyzer's library classpath).
 */
class KotlinDelegatedPropertyTypeTest {
    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    @Test fun memberDelegateWithoutExplicitArgIsTyped() {
        // `var name by boxOf("hi")` — T bound from the value arg → String. `name.length` completes only if the
        // cross-file member delegate is typed at all (defect 1).
        val items = labels("package demo\nfun f(g: Game) { g.name.len| }")
        assertTrue("length" in items, "a cross-file `by` member should be typed (String here); got ${items.take(30)}")
    }

    @Test fun explicitTypeArgOnMemberDelegateFlows() {
        // `var winner by boxOf<Mark?>(null)` — the explicit `<Mark?>` pins the type even though the value arg is
        // `null` (defects 1 + 2 together). `winner?.symbol` completes only if `winner` is `Mark?`.
        val items = labels("package demo\nfun f(g: Game) { g.winner?.sym| }")
        assertTrue("symbol" in items, "`g.winner` should infer `Mark?`; got ${items.take(30)}")
    }

    @Test fun explicitTypeArgOnLocalCallFlows() {
        // Defect 2 isolated on the local path: an explicit type arg with a non-binding `null` value arg.
        val items = labels("package demo\nfun f() { val s = boxOf<Mark?>(null)\n  s.value?.sym| }")
        assertTrue("symbol" in items, "`boxOf<Mark?>(null).value` should be `Mark?`; got ${items.take(30)}")
    }

    @Test fun delegateGenericElementTypePreserved() {
        // `var winningLine by boxOf<List<Int>?>(null)` — the element `Int` must survive, so an indexed read is
        // `Int` (defects 1 + 2). `get(0)?.toLong()` completes only if the element is `Int`.
        val items = labels("package demo\nfun f(g: Game) { g.winningLine?.get(0)?.toL| }")
        assertTrue("toLong" in items, "`g.winningLine` should keep its `Int` element; got ${items.take(30)}")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Seed.kt" to "package demo\n",
                "State.kt" to "package demo\n" +
                    "import kotlin.reflect.KProperty\n" +
                    "class Box<T>(var value: T)\n" +
                    "operator fun <T> Box<T>.getValue(thisRef: Any?, property: KProperty<*>): T = value\n" +
                    "operator fun <T> Box<T>.setValue(thisRef: Any?, property: KProperty<*>, value: T) { this.value = value }\n" +
                    "fun <T> boxOf(value: T): Box<T> = Box(value)\n",
                "Game.kt" to "package demo\n" +
                    "enum class Mark(val symbol: String) { X(\"X\"), O(\"O\") }\n" +
                    "class Game {\n" +
                    "    var name by boxOf(\"hi\")\n" +
                    "        private set\n" +
                    "    var winner by boxOf<Mark?>(null)\n" +
                    "        private set\n" +
                    "    var winningLine by boxOf<List<Int>?>(null)\n" +
                    "        private set\n" +
                    "}\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
