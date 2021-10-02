/*
 * Copyright 2019 Flipkart Internet Pvt. Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flipkart.android.proteus.toolbox;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * HashBiMap
 *
 * @author adityasharat
 */

public class HashBiMap<K, V> implements BiMap<K, V> {

  private final HashMap<K, V> map;
  private final HashMap<V, K> inverse;

  public HashBiMap() {
    map = new HashMap<>();
    inverse = new HashMap<>();
  }

  public HashBiMap(int initialCapacity) {
    map = new HashMap<>(initialCapacity);
    inverse = new HashMap<>(initialCapacity);
  }

  public HashBiMap(int initialCapacity, float loadFactor) {
    map = new HashMap<>(initialCapacity, loadFactor);
    inverse = new HashMap<>(initialCapacity, loadFactor);
  }

  @Nullable
  @Override
  public V put(@Nullable K key, @Nullable V value) {
    return put(key, value, false);
  }

  @Nullable
  @Override
  public V put(@Nullable K key, @Nullable V value, boolean force) {
    if (force && inverse.containsKey(value)) {
      throw new IllegalStateException(value + " is already exists!");
    }
    inverse.put(value, key);
    return map.put(key, value);
  }

  @Nullable
  @Override
  public V getValue(@NonNull K key) {
    return map.get(key);
  }

  @Nullable
  @Override
  public K getKey(@NonNull V value) {
    return inverse.get(value);
  }

  @Override
  public void putAll(@NonNull Map<? extends K, ? extends V> map) {
    for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }

  @NonNull
  @Override
  public Set<V> values() {
    return inverse.keySet();
  }

  @NonNull
  @Override
  public BiMap<V, K> inverse() {
    BiMap<V, K> temp = new HashBiMap<>(inverse.size());
    for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
      temp.put(entry.getValue(), entry.getKey());
    }
    return temp;
  }
}
