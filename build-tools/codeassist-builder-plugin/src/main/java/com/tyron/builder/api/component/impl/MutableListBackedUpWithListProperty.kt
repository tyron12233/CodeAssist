package com.tyron.builder.api.component.impl

import com.tyron.builder.gradle.internal.LoggerWrapper
import org.gradle.api.provider.ListProperty

/**
 * Minimal implementation of [MutableList] based on a [ListProperty]
 *
 * Because some methods like [MutableList.get] will require reading from the [ListProperty],
 * instantiation of this class should probably be guarded by
 * [com.tyron.builder.gradle.options.BooleanOption.ENABLE_LEGACY_API]
 */
class MutableListBackedUpWithListProperty<E>(
    private val propertyList: ListProperty<E>,
    private val propertyName: String,
): java.util.AbstractList<E>(), MutableList<E>{

    private val logger = LoggerWrapper.getLogger(MutableListBackedUpWithListProperty::class.java)

    override fun get(index: Int): E  = propertyList.get()[index]

    override val size: Int
        get() = _get().size

    override fun add(element: E): Boolean {
        propertyList.add(element)
        return true
    }

    override fun add(index: Int, element: E) {
        propertyList.add(element)
    }

    override fun clear() {
        propertyList.empty()
    }

    override fun listIterator(): MutableListIterator<E> {
        val mutableIterator = _get().listIterator()
        return object: MutableListIterator<E> by mutableIterator {
            override fun add(element: E) {
                throw UnsupportedOperationException("You cannot add elements to the $propertyName collection")
            }

            override fun remove() {
                throw UnsupportedOperationException("You cannot remove elements from the $propertyName " +
                                                            "collection, use clear() and add back elements you want to retain")
            }
        }
    }

    override fun removeAt(index: Int): E {
        throw NotImplementedError("Cannot selectively remove elements from the $propertyName collection, first clear() the list" +
                " and add back the elements you want to retain")
    }

    override fun toString(): String {
        return _get().toString()
    }

    private fun _get(): MutableList<E> {
        logger.warning("Values of variant API $propertyName are queried and may return non final values, " +
                               "this is unsupported")
        return propertyList.get()
    }
}
