package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.io.EnumeratorIntegerDescriptor;
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;

/**
 * Base implementation for <a href="https://en.wikipedia.org/wiki/Search_engine_indexing#The_forward_index">forward indices</a>
 * that produce single value per single file.
 * <p>
 * Can be used to cache heavy computable file's data while the IDE is indexing.
 */
//@ApiStatus.OverrideOnly
public abstract class SingleEntryFileBasedIndexExtension<V> extends FileBasedIndexExtension<Integer, V>{
  @NonNull
  @Override
  public final KeyDescriptor<Integer> getKeyDescriptor() {
    return EnumeratorIntegerDescriptor.INSTANCE;
  }

  @Override
  public boolean dependsOnFileContent() {
    return true;
  }

  @Override
  public int getCacheSize() {
    return 5;
  }

  @NonNull
  @Override
  public abstract SingleEntryIndexer<V> getIndexer();

  @Override
  public boolean keyIsUniqueForIndexedFile() {
    return true;
  }
}