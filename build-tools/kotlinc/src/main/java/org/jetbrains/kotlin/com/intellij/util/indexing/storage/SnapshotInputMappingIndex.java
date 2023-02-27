package org.jetbrains.kotlin.com.intellij.util.indexing.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputData;

import java.io.Closeable;
import java.io.IOException;

public interface SnapshotInputMappingIndex<Key, Value, Input> extends Closeable {
  @Nullable
  InputData<Key, Value> readData(@NonNull Input content) throws IOException;
}