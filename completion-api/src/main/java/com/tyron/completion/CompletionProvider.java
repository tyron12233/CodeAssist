package com.tyron.completion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * Provides completion items.
 * <p>
 * Register via {@link CompletionContributor#extend(CompletionType, ElementPattern, CompletionProvider)}.
 */
public abstract class CompletionProvider<V extends CompletionParameters> {

  protected CompletionProvider() {
  }

  protected abstract void addCompletions(@NotNull V parameters, @NotNull ProcessingContext context, @NotNull CompletionResultSet result);

  public final void addCompletionVariants(@NotNull final V parameters,
                                          @NotNull ProcessingContext context,
                                          @NotNull final CompletionResultSet result) {
    addCompletions(parameters, context, result);
  }
}