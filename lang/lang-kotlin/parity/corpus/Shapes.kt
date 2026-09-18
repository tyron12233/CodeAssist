package parity

import kotlin.math.max

/** Sealed hierarchies, generics and overrides: what resolution has to walk to answer a member. */
sealed class Shape(val name: String) {
    abstract fun area(): Double
    open fun describe(): String = "$name of area ${area()}"
}

data class Circle(val radius: Double) : Shape("circle") {
    override fun area(): Double = 3.14159 * radius * radius
    override fun describe(): String = super.describe() + " (r=$radius)"
}

data class Rect(val width: Double, val height: Double) : Shape("rect") {
    override fun area(): Double = width * height
    val longest: Double get() = max(width, height)
}

interface Named {
    val displayName: String
    fun greet(): String = "Hello, $displayName"
}

class Box<T : Shape>(private val items: List<T>) : Named {
    override val displayName: String get() = "box of ${items.size}"

    fun largest(): T? = items.maxByOrNull { it.area() }

    fun totalArea(): Double = items.sumOf { it.area() }

    fun names(): List<String> = items.map { it.name }.sorted()
}

fun Shape.isBig(): Boolean = area() > 100.0

fun <T> Iterable<T>.secondOrNull(): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    iterator.next()
    return if (iterator.hasNext()) iterator.next() else null
}
