package com.android.tools.profgen

internal class MutablePrefixTree<T> {
    private val root = Node<T>()

    fun put(prefix: String, value: T) {
        var node: Node<T> = root
        for (char in prefix) {
            node = node.children.getOrPut(char) { Node() }
        }
        node.values.add(value)
    }

    fun firstOrNull(key: String, fn: (T) -> Boolean): T? {
        prefixIterator(key).forEach {
            if (fn(it)) return it
        }
        return null
    }

    fun prefixIterator(key: String) = iterator {
        var node: Node<T>? = root
        var i = 0
        while (node != null && i < key.length) {
            yieldAll(node.values)
            node = node.children[key[i]]
            i++
        }
        // the node might not be null, we might have just hit the end of the prefix.
        // we still want to ensure that we hit all of the "values" below it.
        node?.let {
           yieldAll(node.iterator())
        }
    }

    private class Node<T> {
        val children = mutableMapOf<Char, Node<T>>()
        val values = mutableListOf<T>()

        fun iterator() = iterator {
            val stack = mutableListOf(this@Node)
            while (stack.isNotEmpty()) {
                val node = stack.removeAt(0)
                yieldAll(node.values)
                stack.addAll(node.children.values)
            }
        }
    }
}

