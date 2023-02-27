package org.jetbrains.kotlin.com.intellij.util.containers;

import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.SystemInfoRt;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.Hash;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

@Retention(RetentionPolicy.SOURCE)
@interface Contract {

  String value();

  boolean pure();
}

@Target(ElementType.TYPE_USE)
@Retention(RetentionPolicy.SOURCE)
@interface NonNull {

}

// ContainerUtil requires trove in classpath
public final class CollectionFactory {
  @Contract(value = " -> new", pure = true)
  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentWeakMap() {
    return new ConcurrentWeakHashMap<>(0.75f);
  }

  @Contract(value = "_, -> new", pure = true)
  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentWeakMap(@NonNull HashingStrategy<? super K> strategy) {
    return new ConcurrentWeakHashMap<>(strategy);
  }

  @Contract(value = " -> new", pure = true)
  public static @NonNull <V> ConcurrentMap<@NonNull String, @NonNull V> createConcurrentWeakCaseInsensitiveMap() {
    return new ConcurrentWeakHashMap<String, V>(new HashingStrategy<String>() {

      @Override
      public int hashCode(@Nullable String s) {
        return s.hashCode();
      }

      @Override
      public boolean equals(@Nullable String s,
                            @Nullable String t1) {
        return s != null && s.equalsIgnoreCase(t1);
      }
    });
  }

  @Contract(value = " -> new", pure = true)
  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentWeakValueMap() {
    return new ConcurrentWeakValueHashMap<>();
  }

  @Contract(value = " -> new", pure = true)
  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentSoftValueMap() {
    return new ConcurrentSoftValueHashMap<>();
  }

  @Contract(value = " -> new", pure = true)
  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentWeakIdentityMap() {
    return new ConcurrentWeakHashMap<>(HashingStrategy.identity());
  }

  /**
   * @deprecated use {@link java.util.WeakHashMap} instead
   */
  @Contract(value = " -> new", pure = true)
  @Deprecated
  public static @NonNull <K, V> Map<@NonNull K, V> createWeakMap() {
    return new java.util.WeakHashMap<>();
  }

  /**
   * Weak keys hard values hash map.
   * Null keys are NOT allowed
   * Null values are allowed
   */
  @Contract(value = "_,_,_ -> new", pure = true)
  public static @NonNull <K, V> Map<@NonNull K, V> createWeakMap(int initialCapacity, float loadFactor, @NonNull HashingStrategy<? super K> hashingStrategy) {
    return new WeakHashMap<>(initialCapacity, loadFactor, hashingStrategy);
  }

//  @Contract(value = " -> new", pure = true)
//  public static @NonNull <K,V> Map<@NonNull K,V> createWeakKeySoftValueMap() {
//    return new WeakKeySoftValueHashMap<>();
//  }
//
//  @Contract(value = " -> new", pure = true)
//  public static @NonNull <K,V> Map<@NonNull K,V> createWeakKeyWeakValueMap() {
//    return new WeakKeyWeakValueHashMap<>();
//  }

  @Contract(value = " -> new", pure = true)
  public static @NonNull <K,V> Map<@NonNull K,V> createSoftKeySoftValueMap() {
    return new SoftKeySoftValueHashMap<>();
  }

  @Contract(value = "_,_,_ -> new", pure = true)
  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentWeakKeySoftValueIdentityMap(int initialCapacity,
                                                                                                float loadFactor,
                                                                                                int concurrencyLevel) {
    //noinspection deprecation
    return new ConcurrentWeakKeySoftValueHashMap<>(initialCapacity, loadFactor, concurrencyLevel, HashingStrategy.identity());
  }

  public static @NonNull <K, V> Map<@NonNull K, V> createWeakIdentityMap(int initialCapacity, float loadFactor) {
    return createWeakMap(initialCapacity, loadFactor, HashingStrategy.identity());
  }

  @Contract(value = " -> new", pure = true)
  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentWeakKeyWeakValueMap() {
    return new ConcurrentWeakKeyWeakValueHashMap<>(100, 0.75f, Runtime.getRuntime().availableProcessors(), HashingStrategy.canonical());
  }

  @Contract(value = "_ -> new", pure = true)
  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentWeakKeyWeakValueMap(@NonNull HashingStrategy<? super K> strategy) {
    return new ConcurrentWeakKeyWeakValueHashMap<>(100, 0.75f, Runtime.getRuntime().availableProcessors(), strategy);
  }

  @Contract(value = " -> new", pure = true)
  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentWeakKeyWeakValueIdentityMap() {
    return new ConcurrentWeakKeyWeakValueHashMap<>(100, 0.75f, Runtime.getRuntime().availableProcessors(), HashingStrategy.identity());
  }

//  @Contract(value = "_,_,_,_ -> new", pure = true)
//  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentWeakMap(int initialCapacity,
//                                                                            float loadFactor,
//                                                                            int concurrencyLevel,
//                                                                            @NonNull HashingStrategy<? super K> hashingStrategy) {
//    return new ConcurrentWeakHashMap<>(initialCapacity, loadFactor, concurrencyLevel, hashingStrategy);
//  }

  @Contract(value = " -> new", pure = true)
  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentWeakKeySoftValueMap() {
    return createConcurrentWeakKeySoftValueMap(100, 0.75f, Runtime.getRuntime().availableProcessors(), HashingStrategy.canonical());
  }

  @Contract(value = "_,_,_,_-> new", pure = true)
  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentWeakKeySoftValueMap(int initialCapacity,
                                                                                        float loadFactor,
                                                                                        int concurrencyLevel,
                                                                                        @NonNull HashingStrategy<? super K> hashingStrategy) {
    //noinspection deprecation
    return new ConcurrentWeakKeySoftValueHashMap<>(initialCapacity, loadFactor, concurrencyLevel, hashingStrategy);
  }

  public static @NonNull <T> Map<CharSequence, T> createCharSequenceMap(boolean caseSensitive, int expectedSize, float loadFactor) {
    return new Object2ObjectOpenCustomHashMap<>(expectedSize, loadFactor, FastUtilHashingStrategies.getCharSequenceStrategy(caseSensitive));
  }

  public static @NonNull Set<CharSequence> createCharSequenceSet(boolean caseSensitive, int expectedSize, float loadFactor) {
    return new ObjectOpenCustomHashSet<>(expectedSize, loadFactor, FastUtilHashingStrategies.getCharSequenceStrategy(caseSensitive));
  }

  public static @NonNull Set<CharSequence> createCharSequenceSet(@NonNull List<? extends CharSequence> items) {
    return new ObjectOpenCustomHashSet<>(items, FastUtilHashingStrategies.getCharSequenceStrategy(true));
  }

//  public static @NonNull Set<CharSequence> createCharSequenceSet(boolean caseSensitive, int expectedSize) {
//    return new ObjectOpenCustomHashSet<>(expectedSize, FastUtilHashingStrategies.getCharSequenceStrategy(caseSensitive));
//  }

  public static @NonNull Set<CharSequence> createCharSequenceSet(boolean caseSensitive) {
    return new ObjectOpenCustomHashSet<>(FastUtilHashingStrategies.getCharSequenceStrategy(caseSensitive));
  }

  public static @NonNull <T> Map<CharSequence, T> createCharSequenceMap(boolean caseSensitive) {
    return new Object2ObjectOpenCustomHashMap<>(FastUtilHashingStrategies.getCharSequenceStrategy(caseSensitive));
  }

  public static @NonNull <T> Map<CharSequence, T> createCharSequenceMap(int capacity, float loadFactory, boolean caseSensitive) {
    return new Object2ObjectOpenCustomHashMap<>(capacity, loadFactory, FastUtilHashingStrategies.getCharSequenceStrategy(caseSensitive));
  }

  public static @NonNull Set<String> createCaseInsensitiveStringSet() {
    return new ObjectOpenCustomHashSet<>(FastUtilHashingStrategies.getCaseInsensitiveStringStrategy());
  }

  public static @NonNull Set<String> createCaseInsensitiveStringSet(@NonNull Collection<String> items) {
    return new ObjectOpenCustomHashSet<>(items, FastUtilHashingStrategies.getCaseInsensitiveStringStrategy());
  }
//  public static @NonNull Set<String> createCaseInsensitiveStringSet(int initialSize) {
//    return new ObjectOpenCustomHashSet<>(initialSize, FastUtilHashingStrategies.getCaseInsensitiveStringStrategy());
//  }

  public static <V> @NonNull Map<String, V> createCaseInsensitiveStringMap() {
    return new Object2ObjectOpenCustomHashMap<>(FastUtilHashingStrategies.getCaseInsensitiveStringStrategy());
  }
//
//  public static <V> @NonNull Map<String, V> createCaseInsensitiveStringMap(int expectedSize) {
//    return new Object2ObjectOpenCustomHashMap<>(expectedSize, FastUtilHashingStrategies.getCaseInsensitiveStringStrategy());
//  }
//
//  public static <V> @NonNull Map<String, V> createCaseInsensitiveStringMap(@NonNull Map<String, V> source) {
//    return new Object2ObjectOpenCustomHashMap<>(source, FastUtilHashingStrategies.getCaseInsensitiveStringStrategy());
//  }

  @Contract(value = "_,_,_ -> new", pure = true)
  @SuppressWarnings("SameParameterValue")
  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentSoftKeySoftValueMap(int initialCapacity,
                                                                                        float loadFactor,
                                                                                        int concurrencyLevel) {
    return new ConcurrentSoftKeySoftValueHashMap<>(initialCapacity, loadFactor, concurrencyLevel, HashingStrategy.canonical());
  }

  @Contract(value = "_,_,_ -> new", pure = true)
  @SuppressWarnings("SameParameterValue")
  static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentSoftKeySoftValueIdentityMap(int initialCapacity,
                                                                                         float loadFactor,
                                                                                         int concurrencyLevel) {
    return new ConcurrentSoftKeySoftValueHashMap<>(initialCapacity, loadFactor, concurrencyLevel, HashingStrategy.identity());
  }

  public static @NonNull Set<String> createFilePathSet() {
    return SystemInfoRt.isFileSystemCaseSensitive ? new HashSet<>() : createCaseInsensitiveStringSet();
  }

  public static @NonNull Set<String> createFilePathSet(int expectedSize) {
    return createFilePathSet(expectedSize, SystemInfoRt.isFileSystemCaseSensitive);
  }

  public static @NonNull Set<String> createFilePathSet(int expectedSize, boolean isFileSystemCaseSensitive) {
    return isFileSystemCaseSensitive
           ? new HashSet<>(expectedSize)
           : new ObjectOpenCustomHashSet<>(expectedSize, expectedSize, FastUtilHashingStrategies.getCaseInsensitiveStringStrategy());
  }

  public static @NonNull Set<String> createFilePathSet(@NonNull Collection<String> paths, boolean isFileSystemCaseSensitive) {
    return isFileSystemCaseSensitive
           ? new HashSet<>(paths)
           : new ObjectOpenCustomHashSet<>(paths, FastUtilHashingStrategies.getCaseInsensitiveStringStrategy());
  }

  public static @NonNull Set<String> createFilePathSet(String @NonNull [] paths, boolean isFileSystemCaseSensitive) {
    //noinspection SSBasedInspection
    return isFileSystemCaseSensitive
           ? new HashSet<>(Arrays.asList(paths))
           : new ObjectOpenCustomHashSet<>(Arrays.asList(paths), (float) paths.length, FastUtilHashingStrategies.getCaseInsensitiveStringStrategy());
  }

  public static @NonNull Set<String> createFilePathSet(@NonNull Collection<String> paths) {
    return createFilePathSet(paths, SystemInfoRt.isFileSystemCaseSensitive);
  }

  public static @NonNull <V> Map<String, V> createFilePathMap() {
    return SystemInfoRt.isFileSystemCaseSensitive ? new HashMap<>() : createCaseInsensitiveStringMap();
  }

  public static @NonNull <V> Map<String, V> createFilePathMap(int expectedSize) {
    return createFilePathMap(expectedSize, SystemInfoRt.isFileSystemCaseSensitive);
  }

  public static @NonNull <V> Map<String, V> createFilePathMap(int expectedSize, boolean isFileSystemCaseSensitive) {
    return isFileSystemCaseSensitive
           ? new HashMap<>(expectedSize)
           : new Object2ObjectOpenCustomHashMap<>(expectedSize, expectedSize, FastUtilHashingStrategies.getCaseInsensitiveStringStrategy());
  }
//
//  public static @NonNull Set<String> createFilePathLinkedSet() {
//    return SystemInfoRt.isFileSystemCaseSensitive
//           ? createSmallMemoryFootprintLinkedSet()
//           : new ObjectLinkedOpenCustomHashSet<>(FastUtilHashingStrategies.getCaseInsensitiveStringStrategy());
//  }
//
//  /**
//   * Create linked map with key hash strategy according to file system path case sensitivity.
//   */
//  public static @NonNull <V> Map<String, V> createFilePathLinkedMap() {
//    return SystemInfoRt.isFileSystemCaseSensitive
//           ? createSmallMemoryFootprintLinkedMap()
//           : new Object2ObjectLinkedOpenCustomHashMap<>(FastUtilHashingStrategies.getCaseInsensitiveStringStrategy());
//  }
//
//  /**
//   * Returns a {@link Map} implementation with slightly faster access for very big maps (>100K keys) and a bit smaller memory footprint
//   * than {@link LinkedHashMap}, with predictable iteration order. Null keys and values are permitted.
//   * Use sparingly only when performance considerations are utterly important; in all other cases please prefer {@link LinkedHashMap}.
//   */
//  @Contract(value = "-> new", pure = true)
//  public static <K, V> @NonNull Map<K, V> createSmallMemoryFootprintLinkedMap() {
//    //noinspection SSBasedInspection
//    return new Object2ObjectLinkedOpenHashMap<>();
//  }

  /**
   * Return a {@link Map} implementation with slightly faster access for very big maps (>100K keys) and a bit smaller memory footprint
   * than {@link HashMap}. Null keys and values are permitted. Use sparingly only when performance considerations are utterly important;
   * in all other cases please prefer {@link HashMap}.
   */
  @Contract(value = "-> new", pure = true)
  public static <K, V> @NonNull Map<K, V> createSmallMemoryFootprintMap() {
    //noinspection SSBasedInspection
    return new Object2ObjectOpenHashMap<>();
  }

  /** See {@link #createSmallMemoryFootprintMap()}. */
  @Contract(value = "_ -> new", pure = true)
  public static <K, V> @NonNull Map<K, V> createSmallMemoryFootprintMap(int expected) {
    //noinspection SSBasedInspection
    return new Object2ObjectOpenHashMap<>(expected, 0.75f);
  }

//  /** See {@link #createSmallMemoryFootprintMap()}. */
//  @Contract(value = "_ -> new", pure = true)
//  public static <K, V> @NonNull Map<K, V> createSmallMemoryFootprintMap(@NonNull Map<? extends K, ? extends V> map) {
//    //noinspection SSBasedInspection
//    return new Object2ObjectOpenHashMap<>(map, map.size());
//  }

  /** See {@link #createSmallMemoryFootprintMap()}. */
  @Contract(value = "_,_ -> new", pure = true)
  public static <K, V> @NonNull Map<K, V> createSmallMemoryFootprintMap(int expected, float loadFactor) {
    //noinspection SSBasedInspection
    return new Object2ObjectOpenHashMap<>(expected, loadFactor);
  }

//  /**
//   * Returns a linked-keys (i.e. iteration order is the same as the insertion order) {@link Set} implementation with slightly faster access for very big collection (>100K keys) and a bit smaller memory footprint
//   * than {@link HashSet}. Null keys are permitted. Use sparingly only when performance considerations are utterly important;
//   * in all other cases please prefer {@link HashSet}.
//   */
//  @Contract(value = "-> new", pure = true)
//  public static <K> @NonNull Set<K> createSmallMemoryFootprintLinkedSet() {
//    return new ObjectLinkedOpenHashSet<>();
//  }

  /**
   * Returns a {@link Set} implementation with slightly faster access for very big collections (>100K keys) and a bit smaller memory footprint
   * than {@link HashSet}. Null keys are permitted. Use sparingly only when performance considerations are utterly important;
   * in all other cases please prefer {@link HashSet}.
   */
  @Contract(value = "-> new", pure = true)
  public static <K> @NonNull Set<K> createSmallMemoryFootprintSet() {
    //noinspection SSBasedInspection
    return new ObjectOpenHashSet<>();
  }

  /** See {@link #createSmallMemoryFootprintSet()}. */
  @Contract(value = "_-> new", pure = true)
  public static <K> @NonNull Set<K> createSmallMemoryFootprintSet(int expected) {
    //noinspection SSBasedInspection
    return new ObjectOpenHashSet<>(expected);
  }
//
//  /** See {@link #createSmallMemoryFootprintSet()}. */
//  @Contract(value = "_-> new", pure = true)
//  public static <K> @NonNull Set<K> createSmallMemoryFootprintSet(@NonNull Collection<? extends K> collection) {
//    //noinspection SSBasedInspection
//    return new ObjectOpenHashSet<>(collection, collection.size());
//  }

  /**
   * Soft keys hard values hash map.
   * Null keys are NOT allowed
   * Null values are allowed
   */
  @Contract(value = " -> new", pure = true)
  public static @NonNull <K,V> Map<@NonNull K,V> createSoftMap() {
    return new SoftHashMap<>(4);
  }

//  @Contract(value = "_ -> new", pure = true)
//  static @NonNull <K,V> Map<@NonNull K,V> createSoftMap(@NonNull HashingStrategy<? super K> strategy) {
//    return new SoftHashMap<>(strategy);
//  }

  @Contract(value = " -> new", pure = true)
  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentSoftMap() {
    return new ConcurrentSoftHashMap<>();
  }

//  @Contract(value = "_,_,_,_-> new", pure = true)
//  public static @NonNull <K, V> ConcurrentMap<@NonNull K, @NonNull V> createConcurrentSoftMap(int initialCapacity,
//                                                                                     float loadFactor,
//                                                                                     int concurrencyLevel,
//                                                                                     @NonNull HashingStrategy<? super K> hashingStrategy) {
//    return new ConcurrentSoftHashMap<>(initialCapacity, loadFactor, concurrencyLevel, hashingStrategy);
//  }

//  public static void trimMap(@NonNull Map<?, ?> map) {
//    if (map instanceof Object2ObjectOpenHashMap<?, ?>) {
//      ((Object2ObjectOpenHashMap<?, ?>)map).trim();
//    }
//    else if (map instanceof Object2ObjectOpenCustomHashMap) {
//      ((Object2ObjectOpenCustomHashMap<?, ?>)map).trim();
//    }
//  }
//
//  public static void trimSet(@NonNull Set<?> set) {
//    if (set instanceof ObjectOpenHashSet<?>) {
//      ((ObjectOpenHashSet<?>)set).trim();
//    }
//    else if (set instanceof ObjectOpenCustomHashSet) {
//      ((ObjectOpenCustomHashSet<?>)set).trim();
//    }
//  }

  public static <K,V> @NonNull Map<K,V> createCustomHashingStrategyMap(@NonNull HashingStrategy<? super K> strategy) {
    return new Object2ObjectOpenCustomHashMap<>(adaptStrategy(strategy));
  }


  private static <K> Hash.Strategy<K> adaptStrategy(@NonNull HashingStrategy<? super K> strategy) {
    return new Hash.Strategy<K>() {
      @Override
      public int hashCode(@Nullable K o) {
        return strategy.hashCode(o);
      }

      @Override
      public boolean equals(@Nullable K a, @Nullable K b) {
        return strategy.equals(a, b);
      }
    };
  }

  public static <K,V> @NonNull Map<K,V> createCustomHashingStrategyMap(int expected, @NonNull HashingStrategy<? super K> strategy) {
    return new Object2ObjectOpenCustomHashMap<>(expected, 0.9f, adaptStrategy(strategy));
  }
  public static <K> @NonNull Set<K> createCustomHashingStrategySet(@NonNull HashingStrategy<? super K> strategy) {
    return new ObjectOpenCustomHashSet<>(adaptStrategy(strategy));
  }
//  public static <K> @NonNull Set<K> createLinkedCustomHashingStrategySet(@NonNull HashingStrategy<? super K> strategy) {
//    return new ObjectLinkedOpenCustomHashSet<>(adaptStrategy(strategy));
//  }
}