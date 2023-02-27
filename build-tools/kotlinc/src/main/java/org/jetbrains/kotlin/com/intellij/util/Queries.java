package org.jetbrains.kotlin.com.intellij.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;

import java.util.Collection;
import java.util.function.Function;

/**
 * This class is intentionally package local.
 */
public abstract class Queries {

  @NotNull
  static Queries getInstance() {
    return ApplicationManager.getApplication().getService(Queries.class);
  }

  @NotNull
  protected abstract <I, O> Query<O> transforming(@NotNull Query<? extends I> base,
                                                  @NotNull Function<? super I, ? extends Collection<? extends O>> transformation);

  @NotNull
  protected abstract <I, O> Query<O> flatMapping(@NotNull Query<? extends I> base,
                                                 @NotNull Function<? super I, ? extends Query<? extends O>> mapper);
}