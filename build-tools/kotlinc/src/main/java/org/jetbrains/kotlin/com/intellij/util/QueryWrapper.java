package org.jetbrains.kotlin.com.intellij.util;

import org.jetbrains.annotations.ApiStatus.Experimental;
import org.jetbrains.annotations.NotNull;

@Experimental
@FunctionalInterface
public interface QueryWrapper<Result> {
  boolean wrapExecution(@NotNull Processor<? super @NotNull Processor<? super Result>> executor,
                        @NotNull Processor<? super Result> consumer);
}