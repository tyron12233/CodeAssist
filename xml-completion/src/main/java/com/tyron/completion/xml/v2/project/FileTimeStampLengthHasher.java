package com.tyron.completion.xml.v2.project;

import com.google.common.hash.Hashing;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Computes a combined 64-bit hash of time stamp and size of a file.
 */
public class FileTimeStampLengthHasher {
  private static final byte[] NULL_HASH = new byte[8];

  /**
   * Computes a combined 64-bit hash of time stamp and size of a {@link VirtualFile}.
   * Returns an array of 8 zero bytes if the virtual file is null or is not valid.
   */
  @NotNull
  public static byte[] hash(@Nullable File virtualFile) {
    if (virtualFile == null || !virtualFile.exists()) {
      return NULL_HASH;
    }
    return Hashing.sipHash24().newHasher().putLong(virtualFile.lastModified()).putLong(virtualFile.length()).hash().asBytes();
  }
}