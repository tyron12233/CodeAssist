package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Represents {@link DataIndexer} which behaviour can be extended by some kind of extension points.
 * <p>
 * See {@link com.intellij.psi.impl.cache.impl.id.IdIndex}, {@link com.intellij.psi.stubs.StubUpdatingIndex} as examples.
 * </p>
 */
//@ApiStatus.Experimental
public interface CompositeDataIndexer<K, V, SubIndexerType, SubIndexerVersion> extends DataIndexer<K, V, FileContent> {
  /**
   * Calculates sub-indexer type which will be used by indexing algorithm.
   * Usually SubIndexerType it's some extension which build index for a given file.
   *
   * @return null if file is not acceptable for indexing
   * @see CompositeDataIndexer#map(FileContent, Object)
   */
  @Nullable
  SubIndexerType calculateSubIndexer(@NonNull IndexedFile file);

  /**
   * Determine should we load content to provide sub-indexer.
   */
  default boolean requiresContentForSubIndexerEvaluation(@NonNull IndexedFile file) {
    return false;
  }

  /**
   * SubIndexerVersion reflects StubIndexerType persistent version.
   * It should depend only on it and must not use any additional information about IDE setup.
   */
  @NonNull
  SubIndexerVersion getSubIndexerVersion(@NonNull SubIndexerType subIndexerType);

  /**
   * SubIndexerVersion descriptor must depend only on corresponding index version, should be available to read even corresponding SubIndexerType is not exist anymore.
   */
  @NonNull
  KeyDescriptor<SubIndexerVersion> getSubIndexerVersionDescriptor();

  @NonNull
  @Override
  default Map<K, V> map(@NonNull FileContent inputData) {
    SubIndexerType subIndexerType = calculateSubIndexer(inputData);
    return subIndexerType == null ? Collections.emptyMap() : map(inputData, Objects.requireNonNull(subIndexerType));
  }

  /**
   * @return indexed data for an input provided by indexerType argument
   */
  @NonNull
  Map<K, V> map(@NonNull FileContent inputData, @NonNull SubIndexerType indexerType);
}