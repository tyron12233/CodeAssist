package dev.ide.platform

/**
 * A set that compares by REFERENCE, because common Kotlin has no `IdentityHashMap`.
 *
 * It exists for one shape of problem: de-duplicating nodes while walking a tree that was just built. A
 * lowered program is data classes all the way down, so structural equality is defined but expensive (hashing
 * a node hashes its whole subtree) and wrong for the question being asked, which is "have I already walked
 * THIS object". Within one traversal a node is only ever the same instance, so identity is both cheaper and
 * the intended meaning.
 *
 * Deliberately minimal: add and contains. A caller that wants iteration or removal wants a different
 * structure, and every use so far is a visited-set.
 */
expect class IdentitySet<T : Any>() {
    /** True when [element] was not present, matching `MutableSet.add`. */
    fun add(element: T): Boolean

    operator fun contains(element: T): Boolean

    /** What was added, in insertion order. The way out for a caller that has to hand the result on. */
    fun toList(): List<T>

    val size: Int
}
