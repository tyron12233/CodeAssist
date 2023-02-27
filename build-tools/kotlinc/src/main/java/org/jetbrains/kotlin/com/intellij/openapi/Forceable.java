package org.jetbrains.kotlin.com.intellij.openapi;

import java.io.IOException;

public interface Forceable {
  boolean isDirty();
  void force() throws IOException;
}