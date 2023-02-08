package com.tyron.completion.lookup;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

import java.util.List;

/**
 * Methods in this class are not externally synchronized and may be called from several threads;
 * while this class has no mutable state, thus it's thread-safe, subclasses may not be thread safe.
 * It's a responsibility of subclasses to synchronize properly.
 * Please don't call superclass methods like addElement under the lock.
 */
public abstract class Classifier<T> {
  protected final Classifier<T> myNext;
  private final String myName;

  protected Classifier(Classifier<T> next, @NonNls String name) {
    myNext = next;
    myName = name;
  }

  public void addElement(@NotNull T t, @NotNull ProcessingContext context) {
    if (myNext != null) {
      myNext.addElement(t, context);
    }
  }

  @NotNull
  public abstract Iterable<T> classify(@NotNull Iterable<? extends T> source, @NotNull ProcessingContext context);

  /**
   * @return a mapping from the given items to objects (e.g. Comparable instances) used to sort the items in {@link #classify(Iterable, ProcessingContext)}.
   * May return an empty list if there are no suitable objects available.
   * Used for diagnostics and statistic collection.
   */
  @NotNull
  public abstract List<Pair<T, Object>> getSortingWeights(@NotNull Iterable<? extends T> items, @NotNull ProcessingContext context);

  @Nullable
  public final Classifier<T> getNext() {
    return myNext;
  }

  public void removeElement(@NotNull T element, @NotNull ProcessingContext context) {
    if (myNext != null) {
      myNext.removeElement(element, context);
    }
  }

  @NotNull
  public final String getPresentableName() {
    return myName;
  }
}