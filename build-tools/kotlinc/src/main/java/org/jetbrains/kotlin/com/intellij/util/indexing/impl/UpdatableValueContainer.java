package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import org.jetbrains.kotlin.com.intellij.util.indexing.ValueContainer;
import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;

import java.io.DataOutput;
import java.io.IOException;

/**
 * @author Eugene Zhuravlev
 */
public abstract class UpdatableValueContainer<T> extends ValueContainer<T> {

  public abstract void addValue(int inputId, T value);

  public abstract boolean removeAssociatedValue(int inputId);

  private volatile boolean myNeedsCompacting;

  boolean needsCompacting() {
    return myNeedsCompacting;
  }

  void setNeedsCompacting(boolean value) {
    myNeedsCompacting = value;
  }

  public abstract void saveTo(DataOutput out, DataExternalizer<? super T> externalizer) throws IOException;
}