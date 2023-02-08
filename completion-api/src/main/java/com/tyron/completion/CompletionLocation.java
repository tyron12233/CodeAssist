package com.tyron.completion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolder;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

public class CompletionLocation implements UserDataHolder {
  private final CompletionParameters myCompletionParameters;
  private final ProcessingContext myProcessingContext = new ProcessingContext();

  public CompletionLocation(final CompletionParameters completionParameters) {
    myCompletionParameters = completionParameters;
  }

  public CompletionParameters getCompletionParameters() {
    return myCompletionParameters;
  }

  public CompletionType getCompletionType() {
    return myCompletionParameters.getCompletionType();
  }

  public Project getProject() {
    return myCompletionParameters.getPosition().getProject();
  }

  public ProcessingContext getProcessingContext() {
    return myProcessingContext;
  }

  @Override
  public <T> T getUserData(@NotNull Key<T> key) {
    return (T) myProcessingContext.get(key);
  }

  @Override
  public <T> void putUserData(@NotNull Key<T> key, @Nullable T value) {
    myProcessingContext.put(key, value);
  }
}