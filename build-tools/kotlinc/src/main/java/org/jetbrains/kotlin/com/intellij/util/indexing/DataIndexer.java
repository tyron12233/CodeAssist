package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;

import java.util.Map;

/**
 * Implement this interface to map input (such as a file) to a {@code Map<Key, Value>},
 * which will be associated with this input by the {@link InvertedIndex index}.
 *
 * @see com.intellij.util.indexing.SingleEntryIndexer
 * @see com.intellij.util.indexing.CompositeDataIndexer
 * @see com.intellij.util.indexing.SingleEntryCompositeIndexer
 */
public interface DataIndexer<Key, Value, Data> {
  /**
   * Map input to its associated data.
   */
  @NonNull
  Map<Key,Value> map(@NonNull Data inputData);
}