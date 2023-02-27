package org.jetbrains.kotlin.com.intellij.util.indexing.snapshot

import org.jetbrains.kotlin.com.intellij.util.indexing.ValueContainer
import org.jetbrains.kotlin.com.intellij.util.indexing.containers.IntIdsIterator
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InvertedIndexValueIterator
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.ValueContainerImpl
import java.util.function.IntPredicate

object EmptyValueContainer: ValueContainer<Nothing>() {
  override fun getValueIterator(): ValueIterator<Nothing> = EmptyValueIterator

  override fun size() = 0
}

private object EmptyValueIterator: InvertedIndexValueIterator<Nothing> {
  override fun next() = throw IllegalStateException()

  override fun getInputIdsIterator(): IntIdsIterator = ValueContainerImpl.EMPTY_ITERATOR

  override fun remove() = throw IllegalStateException()

  override fun getValueAssociationPredicate(): IntPredicate = IntPredicate { false }

  override fun hasNext() = false

  override fun getFileSetObject(): Any? = null
}