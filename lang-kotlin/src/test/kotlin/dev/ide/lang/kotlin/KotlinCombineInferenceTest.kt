package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A lambda parameter of a top-level generic higher-order function must infer its type from a type ARGUMENT of a
 * value argument's type: `combine(flowA, flowB) { a, key -> }` where `combine(Flow<T1>, Flow<T2>, (T1,T2)->R)`.
 * Here `key` must be `Key` (T2, the element of `flowB: Flow<Key>`), not the bare type parameter `T2`.
 */
class KotlinCombineInferenceTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    @Test fun secondLambdaParamInfersElementType() {
        val items = labels(
            "package demo\n" +
                "fun use() {\n" +
                "  val a = MutableStateFlow<MutableList<Food>>(mutableListOf())\n" +
                "  val b = MutableStateFlow<Key>(Key())\n" +
                "  combine(a, b) { allList, key -> key.tex| }\n" +
                "}"
        )
        assertTrue("text" in items, "the 2nd combine lambda param should infer Key (key.text); got $items")
    }

    @Test fun secondParamInfersFromInferredConstructorArg() {
        // `b`'s element type is inferred from the constructor argument (no explicit `<Key>`) — the reported shape.
        val items = labels(
            "package demo\n" +
                "fun use() {\n" +
                "  val a = MutableStateFlow<MutableList<Food>>(mutableListOf())\n" +
                "  val b = MutableStateFlow(Key())\n" +
                "  combine(a, b) { allList, key -> key.tex| }\n" +
                "}"
        )
        assertTrue("text" in items, "key should infer Key even when b's arg is constructor-inferred; got $items")
    }

    @Test fun classPropertyImplicitTypeKeepsTypeArg() {
        // Isolates the root cause independent of combine: a class property with an INFERRED type must keep its
        // generic argument, so `_key.value` is `Key` and `.text` completes.
        val items = labels(
            "package demo\n" +
                "class VM {\n" +
                "  val _key = MutableStateFlow(Key())\n" +
                "  fun f() { _key.value.tex| }\n" +
                "}"
        )
        assertTrue("text" in items, "an implicit-typed class property must keep its type arg (_key.value: Key); got $items")
    }

    @Test fun secondParamInfersFromClassProperties() {
        // The exact reported shape: the flows are CLASS PROPERTIES, `b`'s arg constructor-inferred.
        val items = labels(
            "package demo\n" +
                "class VM {\n" +
                "  val _all = MutableStateFlow<MutableList<Food>>(mutableListOf())\n" +
                "  val _key = MutableStateFlow(Key())\n" +
                "  fun f() { combine(_all, _key) { allList, key -> key.tex| } }\n" +
                "}"
        )
        assertTrue("text" in items, "key should infer Key from a class-property flow; got $items")
    }

    @Test fun firstLambdaParamInfersElementType() {
        val items = labels(
            "package demo\n" +
                "fun use() {\n" +
                "  val a = MutableStateFlow<MutableList<Food>>(mutableListOf())\n" +
                "  val b = MutableStateFlow<Key>(Key())\n" +
                "  combine(a, b) { allList, key -> allList.si| }\n" +
                "}"
        )
        assertTrue("size" in items, "the 1st combine lambda param should infer MutableList<Food> (.size); got $items")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Flows.kt" to (
                    "package demo\n" +
                        "interface Flow<out T>\n" +
                        "class MutableStateFlow<T>(v: T) : Flow<T> { var value: T = v }\n" +
                        "fun <T1, T2, R> combine(a: Flow<T1>, b: Flow<T2>, transform: suspend (T1, T2) -> R): Flow<R> = TODO()\n" +
                        "fun <T1, T2, R> Flow<T1>.combine(b: Flow<T2>, transform: suspend (T1, T2) -> R): Flow<R> = TODO()\n" +
                        "fun <T, R> combine(vararg flows: Flow<T>, transform: suspend (Array<T>) -> R): Flow<R> = TODO()\n" +
                        "class Food\n" +
                        "class Key { val text: String = \"\" }\n"
                    ),
                "Use.kt" to "package demo\n",
            )
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, listOf(stdlibJarPath())))
    }
}
