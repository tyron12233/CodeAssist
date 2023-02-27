package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.function.IntPredicate;

/**
 * Container for set of pairs (value, valueOriginId).
 * <br/>
 * Used in inverted indexes: inverted index has structure [value -> (key, keySourceId)*], so it is implemented
 * as (persistent) Map[Value -> ValueContainer[Key]).
 * <br/>
 * (There is a bit of mess with keys/values labels, since in inverted index keys effectively switch roles
 * with values)
 *
 * @author Eugene Zhuravlev
 */
public abstract class ValueContainer<Value> {
  public interface IntIterator {
    boolean hasNext();

    int next();

    int size();
  }

  @NonNull
  public abstract ValueIterator<Value> getValueIterator();

  public interface ValueIterator<Value> extends Iterator<Value> {
    @NonNull IntIterator getInputIdsIterator();

    @Nullable
    IntPredicate getValueAssociationPredicate();
  }

  public abstract int size();

  @FunctionalInterface
  public interface ContainerAction<T> {
    boolean perform(int id, T value);
  }

  @FunctionalInterface
  public interface ThrowableContainerProcessor<V, T extends Throwable> {
    boolean process(int id, V value) throws T;
  }

  public final boolean forEach(@NonNull ContainerAction<? super Value> action) {
    for (ValueIterator<Value> valueIterator = getValueIterator(); valueIterator.hasNext();) {
      Value value = valueIterator.next();
      for (IntIterator intIterator = valueIterator.getInputIdsIterator(); intIterator.hasNext();) {
        if (!action.perform(intIterator.next(), value)) {
          return false;
        }
      }
    }
    return true;
  }

  public final <T extends Throwable> boolean process(@NonNull ThrowableContainerProcessor<? super Value, T> action) throws T {
    for (ValueIterator<Value> valueIterator = getValueIterator(); valueIterator.hasNext();) {
      Value value = valueIterator.next();
      for (IntIterator intIterator = valueIterator.getInputIdsIterator(); intIterator.hasNext();) {
        if (!action.process(intIterator.next(), value)) {
          return false;
        }
      }
    }
    return true;
  }
}