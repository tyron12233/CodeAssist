package org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.io.KeyValueStore;

import java.io.IOException;

/**
 * Represents key-value storage held by <a href="https://en.wikipedia.org/wiki/Search_engine_indexing#The_forward_index">forward index data structure</a>.
 */
public interface ForwardIndex extends KeyValueStore<Integer, ByteArraySequence> {
  @Nullable
  @Override
  ByteArraySequence get(@NonNull Integer key) throws IOException;

  @Override
  void put(@NonNull Integer key, @Nullable ByteArraySequence value) throws IOException;

  void clear() throws IOException;
}