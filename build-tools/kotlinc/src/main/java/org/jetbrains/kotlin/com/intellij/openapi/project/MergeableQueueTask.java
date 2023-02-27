package org.jetbrains.kotlin.com.intellij.openapi.project;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;

public interface MergeableQueueTask<T extends MergeableQueueTask<T>> extends Disposable {
  @Nullable T tryMergeWith(@NotNull T taskFromQueue);

  void perform(@NotNull ProgressIndicator indicator);
}