package com.tyron.builder.gradle.internal.dsl.decorator

import com.tyron.builder.gradle.internal.dsl.AgpDslLockedException
import com.tyron.builder.gradle.internal.dsl.Lockable
import javax.inject.Inject

/**
 * An implementation of a set for use in AGP DSL that can be locked.
 *
 * This set implementation preserves insertion order.
 *
 * This is intentionally not serializable, as model classes should take copies
 * e.g. [com.google.common.collect.ImmutableList.copyOf]
 */
class LockableSet<T> @Inject @JvmOverloads constructor(
    private val name: String,
    private val delegate: MutableSet<T> = mutableSetOf()
) :  java.util.AbstractSet<T>(), MutableSet<T>, Lockable {

    private var locked = false

    override fun lock() {
        locked = true;
    }

    private inline fun <R>check(action: () -> R): R {
        if (locked) {
            throw AgpDslLockedException(
                "It is too late to modify ${name.removePrefix("_")}\n" +
                        "It has already been read to configure this project.\n" +
                        "Consider either moving this call to be during evaluation,\n" +
                        "or using the variant API."
            )
        }
        return action.invoke()
    }

    override val size: Int get() = delegate.size

    override fun add(element: T): Boolean = check {
        delegate.add(element)
    }

    override fun iterator(): MutableIterator<T> {
        return LockableIterator(delegate.iterator())
    }

    private inner class LockableIterator<T>(private val delegate: MutableIterator<T>): MutableIterator<T> {
        override fun hasNext(): Boolean = delegate.hasNext()
        override fun next(): T = delegate.next()
        override fun remove() = check {
            delegate.remove()
        }
    }

}
