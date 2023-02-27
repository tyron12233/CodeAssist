package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;

/**
 * Represents index data format specification, namely
 * serialization format for keys & values,
 * and a mapping from input to indexing data.
 *
 * To create index corresponding to any extension
 * one could use {@link com.intellij.util.indexing.impl.MapReduceIndex}.
 */
public abstract class IndexExtension<Key, Value, Input> {
  /**
   * @return unique name identifier of index extension
   */
  @NonNull
  public abstract IndexId<Key, Value> getName();

  /**
   * @return indexer which determines the procedure how input should be transformed to indexed data
   */
  @NonNull
  public abstract DataIndexer<Key, Value, Input> getIndexer();

  @NonNull
  public abstract KeyDescriptor<Key> getKeyDescriptor();

  @NonNull
  public abstract DataExternalizer<Value> getValueExternalizer();

  public abstract int getVersion();
}