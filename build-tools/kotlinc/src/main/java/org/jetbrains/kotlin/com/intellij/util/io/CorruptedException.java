package org.jetbrains.kotlin.com.intellij.util.io;

import java.io.IOException;
import java.nio.file.Path;

public class CorruptedException extends IOException {
  public CorruptedException(Path file) {
    this("Storage corrupted " + file);
  }

  protected CorruptedException(String message) {
    super(message);
  }
}