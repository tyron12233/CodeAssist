package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;

public interface ValueSerializationProblemReporter {
  void reportProblem(@NonNull Exception exception);
}