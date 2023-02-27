package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;

import java.nio.file.Path;

public class VersionUpdatedException extends CorruptedException {
  VersionUpdatedException(@NonNull Path file) {
    super("Storage version updated, file = " + file);
  }

  VersionUpdatedException(@NonNull Path file, @NonNull Object expectedVersion, @NonNull Object actualVersion) {
    super("Storage version updated" +
          ", file = " + file +
          ", expected version = " + expectedVersion +
          ", actual version = " + actualVersion);
  }
}