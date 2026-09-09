package dev.ide.fakecompose

/**
 * Mirrors Compose's `Modifier`: an interface whose companion object IS-A `FakeModifier`. A bare
 * `FakeModifier.` reference therefore resolves to the companion instance, so extension functions declared on
 * the interface apply — exactly how `Modifier.padding`/`background` are reached. Compiled into the test
 * classpath so the binary (`@Metadata`) completion path can be exercised without the Compose toolchain.
 */
interface FakeModifier {
    companion object : FakeModifier {
        /** A companion-object member, like Compose's `Color.Black` — reached via a bare `FakeModifier.`. */
        val Unset: FakeModifier get() = this
    }
}

fun FakeModifier.fakePadding(all: Int): FakeModifier = this

fun FakeModifier.fakeBackground(): FakeModifier = this

/**
 * Mirrors Compose's `CardDefaults`/`MaterialTheme`: a top-level `object` singleton. A bare `FakeDefaults.`
 * reference denotes the INSTANCE, so its members (`fakeColors`, `theme`) are reached like an instance's —
 * exercising the binary (`@Metadata`) object-member completion path. `theme` returns another object so a
 * `FakeDefaults.theme.` chain can be checked too.
 */
object FakeDefaults {
    fun fakeColors(): FakeModifier = FakeModifier.Unset
    val theme: FakeTheme get() = FakeTheme
}

object FakeTheme {
    val scheme: FakeModifier get() = FakeModifier.Unset
}

/**
 * A scope holding a MEMBER extension (`FakeModifier.scopedPad`), like Compose's `RowScope.weight`: it has a
 * dispatch receiver (`FakeScope`) and so is NOT importable by `package.name` — it must be called with the
 * scope in scope. Completion may still surface it on `FakeModifier.`, but accepting it must add no import.
 */
interface FakeScope {
    fun FakeModifier.scopedPad(): FakeModifier
    /** A member extension WITH a default parameter, mirroring `RowScope.weight(weight, fill = true)`. */
    fun FakeModifier.scopedWeight(weight: Int, fill: Boolean = true): FakeModifier
}

/** A SECOND scope declaring the SAME `FakeModifier.scopedWeight` — mirroring `ColumnScope.weight` alongside
 *  `RowScope.weight`. Both are keyed on `FakeModifier`, so a `FakeModifier.scopedWeight(...)` call sees the
 *  member extension from BOTH scopes — the multi-owner ambiguity that broke `Modifier.weight` on device. */
interface FakeScope2 {
    fun FakeModifier.scopedWeight(weight: Int, fill: Boolean = true): FakeModifier
}

/** Mirrors Compose's `MutableState`: a `value` holder (a DIRECT member). */
class FakeState<T>(var value: T)

/** Mirrors Compose's `SnapshotStateList`: a `MutableList<T>` that OVERRIDES `add`/`removeAt` (as the real
 *  class does), so each surfaces from BOTH `FakeList` and `MutableList` — the multi-owner override ambiguity
 *  that broke `todos.add(...)`/`todos.removeAt(...)` on device. */
abstract class FakeList<T> : MutableList<T> {
    abstract override fun add(element: T): Boolean
    abstract override fun removeAt(index: Int): T
}
