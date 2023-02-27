package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexStorage;

public interface VfsAwareIndexStorage<Key, Value> extends IndexStorage<Key, Value>, org.jetbrains.kotlin.com.intellij.util.io.MeasurableIndexStore {
  boolean processKeys(@NonNull Processor<? super Key> processor, GlobalSearchScope scope, @Nullable IdFilter idFilter) throws StorageException;
}