package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;

public final class BufferExposingByteArrayOutputStream extends UnsyncByteArrayOutputStream {
  public BufferExposingByteArrayOutputStream() {}

  public BufferExposingByteArrayOutputStream(int size) {
    super(size);
  }

  public BufferExposingByteArrayOutputStream(byte[] buffer) {
    super(buffer);
  }

  public BufferExposingByteArrayOutputStream(@NonNull UnsyncByteArrayOutputStream.ByteArrayAllocator allocator, int initialSize) {
    super(allocator, initialSize);
  }

  public byte[] getInternalBuffer() {
    return myBuffer;
  }

  // moves back the written bytes pointer by {@link #size}, to "unwrite" last {@link #size} bytes
  public int backOff(int size) {
    assert size >= 0 : size;
    myCount -= size;
    assert myCount >= 0 : myCount;
    return myCount;
  }
}