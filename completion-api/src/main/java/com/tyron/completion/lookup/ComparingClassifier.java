package com.tyron.completion.lookup;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.FlatteningIterator;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

public abstract class ComparingClassifier<T> extends Classifier<T> {
  private final boolean myNegated;

  protected ComparingClassifier(Classifier<T> next, String name, boolean negated) {
    super(next, name);
    myNegated = negated;
  }

  @Nullable
  public abstract Comparable getWeight(T t, ProcessingContext context);

  @NotNull
  @Override
  public Iterable<T> classify(@NotNull final Iterable<? extends T> source, @NotNull final ProcessingContext context) {
    List<T> nulls = null;
    TreeMap<Comparable, List<T>> map = new TreeMap<>();
    for (T t : source) {
      final Comparable weight = getWeight(t, context);
      if (weight == null) {
          if (nulls == null) {
              nulls = new SmartList<>();
          }
        nulls.add(t);
      } else {
        List<T> list = map.get(weight);
        if (list == null) {
          map.put(weight, list = new SmartList<>());
        }
        list.add(t);
      }
    }

    final List<List<T>> values = new ArrayList<>(myNegated ? map.descendingMap().values() : map.values());
    ContainerUtil.addIfNotNull(values, nulls);

    return new Iterable<T>() {
      @NotNull
      @Override
      public Iterator<T> iterator() {
        return new FlatteningIterator<List<T>, T>(values.iterator()) {
          @Override
          protected Iterator<T> createValueIterator(List<T> group) {
            return myNext.classify(group, context).iterator();
          }
        };
      }
    };
  }

  @NotNull
  @Override
  public List<Pair<T, Object>> getSortingWeights(@NotNull Iterable<? extends T> items, @NotNull final ProcessingContext context) {
    return ContainerUtil.map(items, t -> new Pair<>(t, getWeight(t, context)));
  }
}