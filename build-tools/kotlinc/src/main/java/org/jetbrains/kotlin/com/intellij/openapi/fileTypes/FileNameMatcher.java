package org.jetbrains.kotlin.com.intellij.openapi.fileTypes;

import org.jetbrains.annotations.NotNull;

public interface FileNameMatcher {
  /** @deprecated use {@link #acceptsCharSequence(CharSequence)} */
  @Deprecated
  default boolean accept(@NotNull String fileName) {
    return acceptsCharSequence(fileName);
  }

  /**
   * This method must be overridden in specific matchers, it's default only for compatibility reasons.
   * @return whether the given file name is accepted by this matcher.
   */
  default boolean acceptsCharSequence(@NotNull CharSequence fileName) {
    return accept(fileName.toString());
  }

  @NotNull String getPresentableString();
}