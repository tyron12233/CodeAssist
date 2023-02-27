package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;

import java.util.Collection;

/**
 * An input data diff builder that provides direct access to indexed keys
 */
//@ApiStatus.OverrideOnly
//@ApiStatus.Experimental
public abstract class DirectInputDataDiffBuilder<Key, Value> extends InputDataDiffBuilder<Key, Value> {
  protected DirectInputDataDiffBuilder(int inputId) {
    super(inputId);
  }

  /**
   * @return keys stored for a corresponding {@link InputDataDiffBuilder#myInputId}
   */
  @NonNull
  public abstract Collection<Key> getKeys();
}