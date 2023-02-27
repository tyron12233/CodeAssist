package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;

public interface KeyValueUpdateProcessor<Key, Value> {
  void process(Key key, Value value, int inputId) throws StorageException;
}