package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.util.containers.HashingStrategy;
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.Hash;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import java.util.Map;

public final class IndexStorageUtil {
  public static <K, V> Map<K, V> createKeyDescriptorHashedMap(@NonNull KeyDescriptor<? super K> keyDescriptor) {
    return new Object2ObjectOpenCustomHashMap<>(new Hash.Strategy<K>() {
      @Override
      public int hashCode(@Nullable K o) {
        return o == null ? 0 : keyDescriptor.getHashCode(o);
      }

      @Override
      public boolean equals(@Nullable K a, @Nullable K b) {
        return a == b || (a != null && b != null && keyDescriptor.isEqual(a, b));
      }
    });
  }

  public static <K> HashingStrategy<K> adaptKeyDescriptorToStrategy(@NonNull KeyDescriptor<? super K> keyDescriptor) {
    return new HashingStrategy<K>() {
      @Override
      public int hashCode(@Nullable K o) {
          if (o == null) {
              return 0;
          }
        return keyDescriptor.getHashCode(o);
      }

      @Override
      public boolean equals(@Nullable K a, @Nullable K b) {
          if (a == null && b != null) {
              return false;
          }
          if (b == null && a != null) {
              return false;
          }
        return keyDescriptor.isEqual(a, b);
      }
    };
  }
}