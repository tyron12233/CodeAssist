package dev.ide.vm

/** One fixture call: what to run and, once the JVM oracle has confirmed it, what it returns. */
internal class Case(
    val owner: String,
    val name: String,
    val descriptor: String,
    val args: List<Any?>,
    val expected: Any?,
) {
    override fun toString(): String = "${owner.substringAfterLast('/')}.$name"
}

private const val BASICS = "dev/ide/vm/fixtures/Basics"
private const val COMPOSE = "dev/ide/vm/fixtures/ComposeRuntime"

/**
 * The fixtures every platform runs. [Case.expected] is checked against the real call by the JVM oracle
 * test, so on iOS, where nothing can call the fixture for real, the VM is held to a value the JVM produced.
 */
internal val vmCases: List<Case> = listOf(
    Case(BASICS, "arithmetic", "(I)Ljava/lang/String;", listOf(2000), "acc=61100360 bits=0 f=6637.1675 d=86.6558782025257 div=-3 rem=-1 shift=15 chars=adgjm long=3074457345618258602 byte=44 short=4464 d2i=2147483647 nan=0"),
    Case(BASICS, "strings", "(I)Ljava/lang/String;", listOf(50), "len=140 upper=[THE, QUICK, BROWN] rev=,94,84 idx=14 sub=,2,3,4 trim='x y' repeat=ababab pad=007 cmp=-1 hash=99162322 fmt=42-x-3.14"),
    Case(BASICS, "collections", "()Ljava/lang/String;", emptyList(), "list=[42, 1, 5, 7, 9] counts={the=3, over=1, quick=1, lazy=1, jumps=1, end=1, brown=1, dog=1, fox=1} set=[banana, date, apple, cherry, fig, grape, elderberry] linked={z=1, m=3, b=4} grouped={1=[1, 4, 7, 10, 13, 16, 19], 2=[2, 5, 8, 11, 14, 17, 20], 0=[3, 6, 9, 12, 15, 18]} sorted=[fig, kiwi, pear, banana] deque=[0, 1, 2] contains=true keys=9 sum=64 max=42"),
    Case(BASICS, "lambdas", "()Ljava/lang/String;", emptyList(), "squares=[4, 16, 36, 64, 100] captured=20 compose=7 seq=[1, 3, 9, 27, 81, 243, 729] ref=[1, 2, 3] fold=120"),
    Case(BASICS, "exceptions", "()Ljava/lang/String;", emptyList(), "ioobe nfe:For input string: \"x\" ise:boom cce ae:/ by zero custom:mine:7 runCatching:nope finally=true"),
    Case(BASICS, "classes", "()Ljava/lang/String;", emptyList(), "a=Point(x=1, y=a) b=Point(x=1, y=b) eq=true hash=true colors=RED:0:ff0000, GREEN:1:ff00, BLUE:2:ff valueOf=GREEN shapes=[12.0, 9.0, 0.0] lazy=computed destructured=1b class=Point when=3"),
    Case(BASICS, "lazyCapture", "()Ljava/lang/String;", emptyList(), "owner! again!"),
    Case(BASICS, "coroutines", "()Ljava/lang/String;", emptyList(), "a=21 b=42 sum=6"),
    Case(BASICS, "fib", "(I)I", listOf(18), 2584),
    Case(COMPOSE, "roundTripIntState", "(I)J", listOf(200), 19900L),
    Case(COMPOSE, "roundTripBoxedState", "(I)I", listOf(50), 0),
    Case(COMPOSE, "composeAndRecompose", "()Ljava/lang/String;", emptyList(), "values=0;1; runs=2 invalidated=true rememberSurvived=true"),
)

internal fun newTestVm(out: StringBuilder? = null): Vm =
    Vm(ClassPath(TestClasspath.entries), stdout = { if (out != null) out.append(it) else print(it) })
