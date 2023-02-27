package org.jetbrains.kotlin.com.intellij.util.io.keyStorage;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.Forceable;

import java.io.Closeable;
import java.io.IOException;

public interface AppendableObjectStorage<Data> extends Forceable, Closeable {
  Data read(int addr, boolean checkAccess) throws IOException;

  boolean processAll(@NonNull StorageObjectProcessor<? super Data> processor) throws IOException;

  int append(Data value) throws IOException;

  boolean checkBytesAreTheSame(int addr, Data value) throws IOException;

  void clear() throws IOException;

  void lockRead();

  void unlockRead();

  void lockWrite();

  void unlockWrite();

  boolean isDirty();

  int getCurrentLength();

  @FunctionalInterface
  interface StorageObjectProcessor<Data> {
    boolean process(int offset, Data data);
  }
}