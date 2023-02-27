package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class UnsyncByteArrayOutputStream extends OutputStream implements RepresentableAsByteArraySequence {
  protected byte[] myBuffer;
  protected int myCount;
  private boolean myIsShared;
  private final @NonNull ByteArrayAllocator myAllocator;

  @FunctionalInterface
  public interface ByteArrayAllocator {
    byte[] allocate(int size);
  }

  public UnsyncByteArrayOutputStream() {
    this(32);
  }

  public UnsyncByteArrayOutputStream(int size) {
    this(new byte[size]);
  }
  public UnsyncByteArrayOutputStream(byte[] buffer) {
    myAllocator = size -> new byte[size];
    myBuffer = buffer;
  }

  public UnsyncByteArrayOutputStream(@NonNull ByteArrayAllocator allocator, int initialSize) {
    myAllocator = allocator;
    myBuffer = allocator.allocate(initialSize);
  }

  @Override
  public void write(int b) {
    int newCount = myCount + 1;
    if (newCount > myBuffer.length || myIsShared) {
      grow(newCount);
      myIsShared = false;
    }
    myBuffer[myCount] = (byte)b;
    myCount = newCount;
  }

  private void grow(int newCount) {
    int newLength = newCount > myBuffer.length ? Math.max(myBuffer.length << 1, newCount) : myBuffer.length;
    byte[] newBuffer = myAllocator.allocate(newLength);
    System.arraycopy(myBuffer, 0, newBuffer, 0, myBuffer.length);
    myBuffer = newBuffer;
  }

  @Override
  public void write(byte[] b, int off, int len) {
    if (off < 0 || off > b.length || len < 0 ||
        off + len > b.length || off + len < 0) {
      throw new IndexOutOfBoundsException();
    }
    if (len == 0) {
      return;
    }
    int newCount = myCount + len;
    if (newCount > myBuffer.length || myIsShared) {
      grow(newCount);
      myIsShared = false;
    }
    System.arraycopy(b, off, myBuffer, myCount, len);
    myCount = newCount;
  }

  public void writeTo(OutputStream out) throws IOException {
    out.write(myBuffer, 0, myCount);
  }

  public void reset() {
    myCount = 0;
  }

  public byte[] toNewByteArray() {
    return Arrays.copyOf(myBuffer, myCount);
  }

  public byte[] toByteArray() {
    if (myBuffer.length == myCount) {
      myIsShared = true;
      return myBuffer;
    }
    return toNewByteArray();
  }

  public int size() {
    return myCount;
  }

  @Override
  public String toString() {
    return new String(myBuffer, 0, myCount, StandardCharsets.UTF_8);
  }

  @NonNull
  public ByteArraySequence toByteArraySequence() {
    return myCount == 0 ? new ByteArraySequence(new byte[0]) : new ByteArraySequence(myBuffer, 0, myCount);
  }

  @NonNull
  public InputStream toInputStream() {
    return new UnsyncByteArrayInputStream(myBuffer, 0, myCount);
  }

  @NonNull
  @Override
  public ByteArraySequence asByteArraySequence() {
    return toByteArraySequence();
  }
}