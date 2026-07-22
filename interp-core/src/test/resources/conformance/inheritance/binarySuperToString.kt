open class Widget
class Button : Widget() {
    override fun toString(): String {
        val base: String = super.toString()
        return if (base.length > 0) "labeled" else "empty"
    }
    override fun equals(other: Any?): Boolean = super.equals(other)
    override fun hashCode(): Int = super.hashCode()
}

fun box(): String {
    val b = Button()
    if (b.toString() != "labeled") return "FAIL toString"
    if (!b.equals(b)) return "FAIL equals self"
    if (b.equals(Button())) return "FAIL equals other"
    if (b.hashCode() != b.hashCode()) return "FAIL hashCode stable"
    return "OK"
}
