package org.jetbrains.kotlin.com.intellij.util.io.keyStorage;

import androidx.annotation.NonNull;

import java.io.IOException;

public class NoDataException extends IOException {
  NoDataException(@NonNull String message) {
    super(message);
  }
}