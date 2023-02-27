package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.ValueContainer;

import java.io.Flushable;
import java.io.IOException;

/**
 * @author Eugene Zhuravlev
 */
public interface IndexStorage<Key, Value> extends Flushable {

  void addValue(Key key, int inputId, Value value) throws StorageException;

  void removeAllValues(@NonNull Key key, int inputId) throws StorageException;

  default void updateValue(Key key, int inputId, Value newValue) throws StorageException {
    removeAllValues(key, inputId);
    addValue(key, inputId, newValue);
  }

  void clear() throws StorageException;

  @NonNull
  ValueContainer<Value> read(Key key) throws StorageException;

  void clearCaches();

  void close() throws StorageException;

  @Override
  void flush() throws IOException;

}