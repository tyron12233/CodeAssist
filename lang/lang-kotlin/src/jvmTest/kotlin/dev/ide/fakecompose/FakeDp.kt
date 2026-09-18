package dev.ide.fakecompose

/**
 * Mirrors Compose's `androidx.compose.ui.unit.Dp`: a `@JvmInline value class` over a `Float`. Compiled into
 * the test classpath so the value-class inference + completion paths can be exercised without the Compose
 * toolchain (the `Modifier.offset(x = 4.dp)` shape). On the JVM its methods are name-mangled and its getter
 * returns the underlying `float`; only the `@kotlin.Metadata` retains the real `FakeDp` view.
 */
@JvmInline
value class FakeDp(val value: Float) : Comparable<FakeDp> {
    operator fun plus(other: FakeDp): FakeDp = FakeDp(value + other.value)
    operator fun minus(other: FakeDp): FakeDp = FakeDp(value - other.value)
    operator fun times(other: Float): FakeDp = FakeDp(value * other)
    operator fun div(other: Float): FakeDp = FakeDp(value / other)
    override operator fun compareTo(other: FakeDp): Int = value.compareTo(other.value)
    override fun toString(): String = "$value.dp"
}

/** `10.fakeDp` — the `Int.dp` shape (an extension property returning the value class). */
val Int.fakeDp: FakeDp get() = FakeDp(this.toFloat())

/** `10f.fakeDp` — the `Float.dp` shape. */
val Float.fakeDp: FakeDp get() = FakeDp(this)

/** `2f * 10.dp` / `2 * 10.dp` — the reversed operators (`Float.times(Dp): Dp`, `Int.times(Dp): Dp`) real
 *  Compose declares as top-level extensions in `DpKt`. The RESULT is a `FakeDp`, so a plain number on the
 *  LEFT must not make the whole product read as `Float`/`Int` (the `numericResultType` false-positive). */
operator fun Float.times(other: FakeDp): FakeDp = FakeDp(this * other.value)
operator fun Int.times(other: FakeDp): FakeDp = FakeDp(this * other.value)

/** `FakeModifier.offset(x, y)` — the shape at issue: an extension whose params are the value class, both
 *  defaulted (so a call may omit them, like `Modifier.offset(x = 4.dp)`). */
fun FakeModifier.fakeOffset(x: FakeDp = FakeDp(0f), y: FakeDp = FakeDp(0f)): FakeModifier = this

/** A density scope, like Compose's `Density`. */
interface FakeDensity {
    val density: Float
}

/** The SECOND `offset` overload, mirroring `Modifier.offset(offset: Density.() -> IntOffset)`: same name, a
 *  single receiver-lambda parameter. Present so overload resolution / completion is exercised with two
 *  `fakeOffset` extensions on `FakeModifier` (one value-class-typed, one lambda-typed). */
fun FakeModifier.fakeOffset(offset: FakeDensity.() -> Int): FakeModifier = this
