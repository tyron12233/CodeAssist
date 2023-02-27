package org.jetbrains.kotlin.com.intellij.util.indexing.containers;

import org.jetbrains.kotlin.com.intellij.util.indexing.ValueContainer;

public interface IntIdsIterator extends ValueContainer.IntIterator {
  boolean hasAscendingOrder();

  IntIdsIterator createCopyInInitialState();
}