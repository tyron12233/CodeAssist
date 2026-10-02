package dev.ide.vm.fixtures

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Plain Kotlin, compiled by the real compiler: arithmetic, strings, collections, lambdas, exceptions, data
 * and enum classes. Each driver returns a String that captures what it computed, so a VM run is compared
 * against a real one by one equality.
 */
object Basics {

    @JvmStatic
    fun arithmetic(n: Int): String {
        var acc = 0L
        var bits = 0
        var f = 1.0f
        var d = 0.5
        for (i in 0 until n) {
            acc = (acc + i * 31L xor (i.toLong() shl 3)) % 1_000_000_007L
            bits = bits xor (i ushr 1) xor (i shl 7)
            f = f * 1.0001f + i % 7
            d += kotlin.math.sqrt(i.toDouble()) / (i + 1)
        }
        val chars = (0 until 5).map { ('a' + it * 3) }.joinToString("")
        return "acc=$acc bits=$bits f=$f d=$d div=${-7 / 2} rem=${-7 % 3} shift=${-1 ushr 28} chars=$chars " +
            "long=${Long.MAX_VALUE / 3} byte=${300.toByte()} short=${70000.toShort()} d2i=${1e20.toInt()} nan=${Double.NaN.toInt()}"
    }

    @JvmStatic
    fun strings(n: Int): String {
        val sb = StringBuilder()
        for (i in 0 until n) sb.append(i).append(',')
        val s = sb.toString()
        val words = "The quick brown fox jumps over the lazy dog".split(" ")
        return "len=${s.length} upper=${words.map { it.uppercase() }.take(3)} rev=${s.reversed().take(6)} " +
            "idx=${s.indexOf("7,")} sub=${s.substring(3, 9)} trim='${"  x y  ".trim()}' repeat=${"ab".repeat(3)} " +
            "pad=${"7".padStart(3, '0')} cmp=${"apple".compareTo("banana")} hash=${"hello".hashCode()} fmt=${"%d-%s-%.2f".format(42, "x", 3.14159)}"
    }

    @JvmStatic
    fun collections(): String {
        val list = mutableListOf(5, 3, 9, 1, 7)
        list.sort()
        list.add(0, 42)
        list.removeAt(2)
        val counts = HashMap<String, Int>()
        for (w in "the quick brown fox jumps over the lazy dog the end".split(" ")) counts[w] = (counts[w] ?: 0) + 1
        val set = hashSetOf("banana", "apple", "cherry", "date", "elderberry", "fig", "grape")
        val linked = mutableMapOf("z" to 1, "a" to 2, "m" to 3)
        linked.remove("a")
        linked["b"] = 4
        val grouped = (1..20).groupBy { it % 3 }
        val sorted = listOf("pear", "fig", "banana", "kiwi").sortedWith(compareBy({ it.length }, { it }))
        val deque = ArrayDeque<Int>().apply { addFirst(1); addLast(2); addFirst(0) }
        return "list=$list counts=$counts set=$set linked=$linked grouped=$grouped sorted=$sorted deque=$deque " +
            "contains=${set.contains("fig")} keys=${counts.keys.size} sum=${list.sum()} max=${list.maxOrNull()}"
    }

    @JvmStatic
    fun lambdas(): String {
        val squares = (1..10).map { it * it }.filter { it % 2 == 0 }
        var captured = 0
        val add: (Int) -> Unit = { captured += it }
        repeat(5) { add(it) }
        val compose = { x: Int -> x + 1 }.let { f -> { x: Int -> f(f(x)) } }
        val seq = generateSequence(1) { it * 3 }.takeWhile { it < 1000 }.toList()
        val ref = listOf("a", "bb", "ccc").map(String::length)
        val runnable = Runnable { captured *= 2 }
        runnable.run()
        return "squares=$squares captured=$captured compose=${compose(5)} seq=$seq ref=$ref fold=${(1..5).fold(1) { a, b -> a * b }}"
    }

    @JvmStatic
    fun exceptions(): String {
        val out = StringBuilder()
        try { listOf<Int>()[3] } catch (e: IndexOutOfBoundsException) { out.append("ioobe ") }
        try { "x".toInt() } catch (e: NumberFormatException) { out.append("nfe:${e.message} ") }
        try { error("boom") } catch (e: IllegalStateException) { out.append("ise:${e.message} ") }
        try { val a: Any = "s"; a as Int } catch (e: ClassCastException) { out.append("cce ") }
        try { 1 / (out.length - out.length) } catch (e: ArithmeticException) { out.append("ae:${e.message} ") }
        try { throw Custom("mine", 7) } catch (e: Custom) { out.append("custom:${e.message}:${e.code} ") }
        val r = runCatching { require(false) { "nope" } }
        out.append("runCatching:${r.exceptionOrNull()?.message} ")
        var finallyRan = false
        try { try { throw RuntimeException("inner") } finally { finallyRan = true } } catch (e: RuntimeException) { out.append("finally=$finallyRan") }
        return out.toString()
    }

    class Custom(message: String, val code: Int) : Exception(message)

    data class Point(val x: Int, val y: String)

    enum class Color(val hex: Int) { RED(0xFF0000), GREEN(0x00FF00), BLUE(0x0000FF) }

    sealed interface Shape
    class Circle(val r: Double) : Shape
    class Square(val side: Double) : Shape
    object Empty : Shape

    private fun area(s: Shape): Double = when (s) {
        is Circle -> 3.0 * s.r * s.r
        is Square -> s.side * s.side
        Empty -> 0.0
    }

    @JvmStatic
    fun classes(): String {
        val a = Point(1, "a")
        val b = a.copy(y = "b")
        val colors = Color.entries.joinToString { "${it.name}:${it.ordinal}:${it.hex.toString(16)}" }
        val shapes = listOf(Circle(2.0), Square(3.0), Empty).map(::area)
        val lazyValue by lazy { "computed" }
        val (x, y) = b
        return "a=$a b=$b eq=${a == Point(1, "a")} hash=${a.hashCode() == Point(1, "a").hashCode()} " +
            "colors=$colors valueOf=${Color.valueOf("GREEN")} shapes=$shapes lazy=$lazyValue destructured=$x$y " +
            "class=${a.javaClass.simpleName} when=${when (Color.BLUE) { Color.RED -> 1; Color.GREEN -> 2; Color.BLUE -> 3 }}"
    }

    class Box(val s: String)
    class Holder(owner: String) { val box by lazy { Box(owner + "!") } }

    @JvmStatic
    fun lazyCapture(): String = Holder("owner").box.s + " " + Holder("again").box.s

    /** Work handed to other dispatchers and waited for, the way a ViewModel or a repository does it. */
    @JvmStatic
    fun coroutines(): String = runBlocking {
        val a = withContext(Dispatchers.Default) { 20 + 1 }
        val b = async(Dispatchers.IO) { a * 2 }
        var sum = 0
        flowOf(1, 2, 3).collect { sum += it }
        delay(1)
        "a=$a b=${b.await()} sum=$sum"
    }

    @JvmStatic
    fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)

    /** A tight loop, for measuring interpreted throughput. */
    @JvmStatic
    fun loop(n: Int): Long {
        var acc = 0L
        for (i in 0 until n) acc += (i xor (i shr 3)) and 0xFF
        return acc
    }
}
