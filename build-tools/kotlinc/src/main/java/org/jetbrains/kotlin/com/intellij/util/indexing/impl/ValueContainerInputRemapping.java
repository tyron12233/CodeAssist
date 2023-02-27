package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;

/**
 * Represents remapping of {@code inputId}-s stored in {@link ValueContainerImpl} to fileIds.
 *
 * <p>Usually it is just an identity mapping and {@code inputId == fileId}.
 * But sometimes it can be {@code hashId -> many fileId-s},
 * when multiple files have the same content hash.
 */
@FunctionalInterface
public interface ValueContainerInputRemapping {
  ValueContainerInputRemapping IDENTITY = inputId -> new int[]{inputId};

  // one of: int or int[]. Object is being used here to avoid additional allocations
  @NonNull
  Object remap(int inputId);
}