class Node(val v: Int)

fun box(): String {
    val a = Node(1)
    val b = a
    val c = Node(1)
    if (!(a === b)) return "FAIL same ref"
    if (a === c) return "FAIL diff ref"
    if (!(a !== c)) return "FAIL diff ref !=="
    if (a !== b) return "FAIL same ref !=="
    return "OK"
}
