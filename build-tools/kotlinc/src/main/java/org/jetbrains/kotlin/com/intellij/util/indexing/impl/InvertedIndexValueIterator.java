package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.indexing.ValueContainer;

import java.util.function.IntPredicate;

//@ApiStatus.Internal
public interface InvertedIndexValueIterator<Value> extends ValueContainer.ValueIterator<Value> {
  @Override
  @NonNull
  IntPredicate getValueAssociationPredicate();

  Object getFileSetObject();
}