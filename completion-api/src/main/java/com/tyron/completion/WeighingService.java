package com.tyron.completion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.KeyedExtensionCollector;

import java.util.List;

public final class WeighingService {
  private static final KeyedExtensionCollector<Weigher, Key> COLLECTOR = new KeyedExtensionCollector<>("com.intellij.weigher");

  private WeighingService() { }

  @NotNull
  public static <T, Loc> WeighingComparable<T, Loc> weigh(Key<? extends Weigher<T, Loc>> key, T element, @Nullable Loc location) {
    return weigh(key, new Computable.PredefinedValueComputable<>(element), location);
  }

  @NotNull
  public static <T, Loc> WeighingComparable<T, Loc> weigh(Key<? extends Weigher<T, Loc>> key,
                                                          Computable<? extends T> element,
                                                          @Nullable Loc location) {
    @SuppressWarnings("unchecked") Weigher<T, Loc>[] array = getWeighers(key).toArray(new Weigher[0]);
    return new WeighingComparable<>(element, location, array);
  }

  public static <T, Loc> List<Weigher> getWeighers(Key<? extends Weigher<T, Loc>> key) {
    return COLLECTOR.forKey(key);
  }
}