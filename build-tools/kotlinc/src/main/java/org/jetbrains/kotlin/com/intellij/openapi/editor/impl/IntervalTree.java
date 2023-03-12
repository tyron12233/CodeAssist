package org.jetbrains.kotlin.com.intellij.openapi.editor.impl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.util.Processor;

interface IntervalTree<T> {
  boolean processAll(@NotNull Processor<? super T> processor);
  boolean processOverlappingWith(int start, int end, @NotNull Processor<? super T> processor);
  boolean processContaining(int offset, @NotNull Processor<? super T> processor);

  boolean removeInterval(@NotNull T interval);
  boolean processOverlappingWithOutside(int start, int end, @NotNull Processor<? super T> processor);
}