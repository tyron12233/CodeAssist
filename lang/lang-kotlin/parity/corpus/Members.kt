package parity

/** Companions, objects, delegation, operators and inline/reified: the odd corners of member resolution. */
object Registry {
    private val known = mutableMapOf<String, Shape>()

    fun put(shape: Shape) { known[shape.name] = shape }
    fun get(name: String): Shape? = known[name]
    val size: Int get() = known.size
}

class Counter {
    companion object {
        const val START = 0
        fun zero(): Counter = Counter()
    }

    var value: Int = START
        private set

    operator fun plus(other: Int): Counter = Counter().also { it.value = value + other }
    operator fun get(index: Int): Int = value + index
    operator fun invoke(): Int = value
}

class LoggingNamed(private val delegate: Named) : Named by delegate

inline fun <reified T> List<*>.ofType(): List<T> = filterIsInstance<T>()

typealias ShapeTable = Map<String, Shape>

fun table(shapes: List<Shape>): ShapeTable = shapes.associateBy { it.name }

suspend fun slowArea(shape: Shape): Double {
    val base = shape.area()
    return base * 2
}

fun useAll(shapes: List<Shape>) {
    val counter = Counter.zero()
    val bumped = counter + 3
    val table = table(shapes)
    val circles = shapes.ofType<Circle>()
    Registry.put(shapes.first())
    println(bumped[1])
    println(table.keys.sorted())
    println(circles.map { it.radius })
    println(Registry.size)
}
