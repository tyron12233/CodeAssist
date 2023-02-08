package com.tyron.completion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;

public class InternalCompletionSettings {
  @NotNull
  public static InternalCompletionSettings getInstance() {
    return ApplicationManager.getApplication().getService(InternalCompletionSettings.class);
  }

  public boolean mayStartClassNameCompletion(CompletionResultSet result) {
    return StringUtil.isNotEmpty(result.getPrefixMatcher().getPrefix());
  }
}