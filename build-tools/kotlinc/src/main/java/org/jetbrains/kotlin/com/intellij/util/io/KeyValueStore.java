package org.jetbrains.kotlin.com.intellij.util.io;

import java.io.Closeable;
import java.io.IOException;

public interface KeyValueStore<K, V> extends Closeable {
  V get(K key) throws IOException;

  void put(K key, V value) throws IOException;

  void force();
}