package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.util.indexing.DataIndexer;

/**
 * An exception thrown by implementations of the {@link DataIndexer#map(Object)}.
 * It carries additional information on a {@link #getClassToBlame() class to blame},
 * which is used to identify the origin plugin throwing an exception.
 */
//@ApiStatus.Experimental
public final class MapReduceIndexMappingException extends RuntimeException {
  private final Class<?> myClassToBlame;

  public MapReduceIndexMappingException(@NonNull Throwable cause, @Nullable Class<?> classToBlame) {
    super(cause);
    myClassToBlame = classToBlame;
  }

  public @Nullable Class<?> getClassToBlame() {
    return myClassToBlame;
  }
}