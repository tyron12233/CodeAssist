package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.Condition;
import org.jetbrains.kotlin.com.intellij.util.io.IOCancellationCallbackHolder;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntIterator;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntSet;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntSets;

import java.util.Collection;
import java.util.function.IntPredicate;

public final class InvertedIndexUtil {
  @NonNull
  public static <K, V, I> IntSet collectInputIdsContainingAllKeys(@NonNull InvertedIndex<? super K, V, I> index,
                                                                  @NonNull Collection<? extends K> dataKeys,
                                                                  @Nullable Condition<? super V> valueChecker,
                                                                  @Nullable IntPredicate idChecker)
    throws StorageException {
    IntSet mainIntersection = null;

    for (K dataKey : dataKeys) {
      IOCancellationCallbackHolder.checkCancelled();

      IntSet copy = new IntOpenHashSet();
      ValueContainer<V> container = index.getData(dataKey);

      for (ValueContainer.ValueIterator<V> valueIt = container.getValueIterator(); valueIt.hasNext(); ) {
        final V value = valueIt.next();
        if (valueChecker != null && !valueChecker.value(value)) {
          continue;
        }
        IOCancellationCallbackHolder.checkCancelled();

        ValueContainer.IntIterator iterator = valueIt.getInputIdsIterator();

        final IntPredicate predicate;
        if (mainIntersection == null || iterator.size() < mainIntersection.size() || (predicate = valueIt.getValueAssociationPredicate()) == null) {
          while (iterator.hasNext()) {
            final int id = iterator.next();
            if (mainIntersection == null && (idChecker == null || idChecker.test(id)) ||
                mainIntersection != null && mainIntersection.contains(id)) {
              copy.add(id);
            }
          }
        }
        else {
          for (IntIterator intIterator = mainIntersection.iterator(); intIterator.hasNext(); ) {
            int id = intIterator.nextInt();
            if (predicate.test(id) && (idChecker == null || idChecker.test(id))) {
              copy.add(id);
            }
          }
        }
      }

      mainIntersection = copy;
      if (mainIntersection.isEmpty()) {
        return IntSets.EMPTY_SET;
      }
    }

    return mainIntersection == null ? IntSets.EMPTY_SET : mainIntersection;
  }

  @NonNull
  public static <K, V, I> IntSet collectInputIdsContainingAnyKey(@NonNull InvertedIndex<? super K, V, I> index,
                                                                 @NonNull Collection<? extends K> dataKeys,
                                                                 @Nullable Condition<? super V> valueChecker,
                                                                 @Nullable IntPredicate idChecker) throws StorageException {
    IntSet result = null;
    for (K dataKey : dataKeys) {
      IOCancellationCallbackHolder.checkCancelled();
      ValueContainer<V> container = index.getData(dataKey);
      for (ValueContainer.ValueIterator<V> valueIt = container.getValueIterator(); valueIt.hasNext(); ) {
        V value = valueIt.next();
        if (valueChecker != null && !valueChecker.value(value)) {
          continue;
        }
        IOCancellationCallbackHolder.checkCancelled();
        ValueContainer.IntIterator iterator = valueIt.getInputIdsIterator();
        while (iterator.hasNext()) {
          int id = iterator.next();
            if (idChecker != null && !idChecker.test(id)) {
                continue;
            }
            if (result == null) {
                result = new IntOpenHashSet();
            }
          result.add(id);
        }
      }
    }
    return result == null ? IntSets.EMPTY_SET : result;
  }
}