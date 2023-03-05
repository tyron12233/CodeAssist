package com.tyron.builder.api.component.impl

open class ReadOnlyMutableCollection<E>(
    private val mutableCollection: MutableCollection<E>,
    private val addErrorMessage: String,
): MutableCollection<E> by mutableCollection {
    override fun iterator(): MutableIterator<E> {
        val iterator = mutableCollection.iterator()
        return object: MutableIterator<E> by iterator {
            override fun remove() {
                throw UnsupportedOperationException("You cannot remove elements from this " +
                                                            "collection, use clear() and add back elements you want to retain")
            }
        }
    }

    override fun add(element: E): Boolean {
        throw UnsupportedOperationException(addErrorMessage)
    }

    override fun addAll(elements: Collection<E>): Boolean {
        throw UnsupportedOperationException(addErrorMessage)
    }

    override fun remove(element: E): Boolean {
        throw UnsupportedOperationException(addErrorMessage)
    }
}
