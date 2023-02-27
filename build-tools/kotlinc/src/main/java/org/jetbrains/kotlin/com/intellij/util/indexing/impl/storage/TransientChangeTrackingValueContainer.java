package org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.util.Computable;
import org.jetbrains.kotlin.com.intellij.util.indexing.ValueContainer;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.ChangeTrackingValueContainer;
import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;

import java.io.DataOutput;

class TransientChangeTrackingValueContainer<Value> extends ChangeTrackingValueContainer<Value> {
  TransientChangeTrackingValueContainer(@NonNull Computable<? extends ValueContainer<Value>> initializer) {
    super(initializer);
  }

  // Resets diff of index value for particular fileId
  void dropAssociatedValue(int inputId) {
    dropMergedData();

    removeFromAdded(inputId);
      if (myInvalidated != null) {
          myInvalidated.remove(inputId);
      }
  }

  @Override
  public void saveTo(DataOutput out, DataExternalizer<? super Value> externalizer) {
    throw new UnsupportedOperationException();
  }
}