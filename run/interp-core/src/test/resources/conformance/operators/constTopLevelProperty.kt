// A top-level `const val` compiles to a static FIELD on its file facade, with no getter at all — unlike an
// ordinary top-level `val`, which gets a static getter. `kotlin.math.PI` is the one every bit of geometry
// reaches for, and reading it threw "no top-level property getter `PI` on `kotlin.math.MathKt`".

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

fun box(): String {
    if (PI < 3.14 || PI > 3.15) return "FAIL PI value: " + PI
    // Through an expression, so it is read rather than folded into a literal by the lowering.
    val turn = (PI * 2.0).toFloat()
    if (turn < 6.28f || turn > 6.29f) return "FAIL turn: " + turn
    // Passed to a function that actually uses it.
    if (abs(sin(PI)) > 0.001) return "FAIL sin(PI)"
    if (cos(PI) > -0.999) return "FAIL cos(PI)"
    return "OK"
}
