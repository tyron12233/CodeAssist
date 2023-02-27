package org.jetbrains.kotlin.com.intellij.util.indexing.containers;

import androidx.annotation.NonNull;

/**
 * Represents random access container of int-s, namely indexed input ids.
 */
public interface RandomAccessIntContainer {
  Object clone();
  boolean add(int value);
  boolean remove(int value);

  @NonNull
  IntIdsIterator intIterator();

  void compact();
  int size();
  boolean contains(int value);

  @NonNull
  RandomAccessIntContainer ensureContainerCapacity(int diff);
}