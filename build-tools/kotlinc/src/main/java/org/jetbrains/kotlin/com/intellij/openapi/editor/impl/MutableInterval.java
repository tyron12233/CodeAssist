package org.jetbrains.kotlin.com.intellij.openapi.editor.impl;

public interface MutableInterval extends Interval {
  void setRange(long scalarRange);
  boolean isValid();
  boolean setValid(boolean value);
}