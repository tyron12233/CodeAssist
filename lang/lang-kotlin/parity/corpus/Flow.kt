package parity

/** Smart casts, `when`, null handling and destructuring: the flow analysis the editor reports on. */
enum class Kind { SMALL, LARGE, UNKNOWN }

fun classify(shape: Shape?): Kind = when {
    shape == null -> Kind.UNKNOWN
    shape.isBig() -> Kind.LARGE
    else -> Kind.SMALL
}

fun describeAny(value: Any?): String {
    if (value is Circle) {
        // Smart cast: `radius` is only reachable once the `is` check narrowed the type.
        return "circle r=${value.radius}"
    }
    return when (value) {
        is Rect -> "rect ${value.width}x${value.height}"
        is String -> value.uppercase()
        is Int -> (value + 1).toString()
        null -> "nothing"
        else -> value.toString()
    }
}

fun summarize(shapes: List<Shape>): String {
    val (big, small) = shapes.partition { it.isBig() }
    val header = big.joinToString(prefix = "[", postfix = "]") { it.name }
    return buildString {
        append(header)
        append(" + ")
        append(small.size)
        for (shape in shapes) {
            appendLine(shape.describe())
        }
    }
}

fun firstLongName(shapes: List<Shape>): String? =
    shapes.firstOrNull { it.name.length > 4 }?.name?.trim()?.takeIf { it.isNotEmpty() }
