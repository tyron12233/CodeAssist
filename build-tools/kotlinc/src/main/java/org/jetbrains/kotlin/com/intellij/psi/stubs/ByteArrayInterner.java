package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.Hash;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.Arrays;

//@ApiStatus.Internal
final class ByteArrayInterner {
  private static final Hash.Strategy<byte[]> BYTE_ARRAY_STRATEGY = new Hash.Strategy<byte[]>() {
    @Override
    public int hashCode(byte[] object) {
      return Arrays.hashCode(object);
    }

    @Override
    public boolean equals(byte[] o1, byte[] o2) {
      return Arrays.equals(o1, o2);
    }
  };
  private final Object2IntMap<byte[]> arrayToStart = new Object2IntOpenHashMap<>();
  final BufferExposingByteArrayOutputStream joinedBuffer = new BufferExposingByteArrayOutputStream();

  int internBytes(byte[] bytes) {
      if (bytes.length == 0) {
          return 0;
      }

    int start = arrayToStart.getInt(bytes);
    if (start == 0) {
      start = joinedBuffer.size() + 1; // should be positive
      arrayToStart.put(bytes, start);
      joinedBuffer.write(bytes, 0, bytes.length);
    }
    return start;
  }
}