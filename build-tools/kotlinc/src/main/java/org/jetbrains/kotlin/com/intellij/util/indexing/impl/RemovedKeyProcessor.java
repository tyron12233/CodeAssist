package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;

public interface RemovedKeyProcessor<Key> {
  void process(Key key, int inputId) throws StorageException;
}