package org.jetbrains.kotlin.com.intellij.util.indexing.containers;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;

import java.util.function.Supplier;

/**
 * Switches used implementation of RandomAccessIntContainer from {@link T} to {@link F} after the collection has passed the size threshold.
 * @param <T> implementation that is used while size < threshold
 * @param <F> implementation that is used once threshold is reached
 */
final public class UpgradableRandomAccessIntContainer<
  T extends RandomAccessIntContainer,
  F extends RandomAccessIntContainer> implements RandomAccessIntContainer {
  private T myInstanceLow;
  private F myInstanceHigh = null;
  private final int myThreshold;
  private final @NonNull InstanceUpgrader<? extends F> myFactoryHigh;

  public UpgradableRandomAccessIntContainer(int sizeThreshold,
                                            @NonNull Supplier<? extends T> factoryLow,
                                            @NonNull InstanceUpgrader<? extends F> factoryHigh) {
    myThreshold = sizeThreshold;
    myInstanceLow = factoryLow.get();
    myFactoryHigh = factoryHigh;
  }

  @FunctionalInterface
  public interface InstanceUpgrader<F extends RandomAccessIntContainer> {
    /**
     * It is allowed not to copy contents of {@code instance} to the newly created container, in that case
     * it will be carried out elementwise using an iterator.
     */
    F createFrom(@NonNull RandomAccessIntContainer instance);
  }

  public void upgrade() {
      if (myInstanceLow == null) {
          return; // already upgraded
      }
    myInstanceHigh = myFactoryHigh.createFrom(myInstanceLow);
    assert myInstanceHigh.size() == 0 || myInstanceHigh.size() == myInstanceLow.size();
    if (myInstanceHigh.size() == 0 && myInstanceLow.size() > 0) {
      for (IntIdsIterator it = myInstanceLow.intIterator(); it.hasNext(); ) {
        myInstanceHigh.add(it.next());
      }
    }
    myInstanceLow = null;
  }

  private void upgradeOnThreshold() {
      if (myInstanceLow != null && myInstanceLow.size() >= myThreshold) {
          upgrade();
      }
  }

  @SuppressWarnings("unchecked")
  @Override
  public Object clone() {
    try {
      UpgradableRandomAccessIntContainer<T, F> copy =
        (UpgradableRandomAccessIntContainer<T, F>)super.clone();
      if (myInstanceHigh != null) {
        copy.myInstanceHigh = (F)myInstanceHigh.clone();
      }
      if (myInstanceLow != null) {
        copy.myInstanceLow = (T)myInstanceLow.clone();
      }
      return copy;
    }
    catch (CloneNotSupportedException e) {
      Logger.getInstance(getClass().getName()).error(e);
      return null;
    }
  }

  @Override
  public boolean add(int value) {
    if (myInstanceHigh != null) {
      return myInstanceHigh.add(value);
    }
    boolean result = myInstanceLow.add(value);
    upgradeOnThreshold();
    return result;
  }

  @Override
  public boolean remove(int value) {
    if (myInstanceHigh != null) {
      return myInstanceHigh.remove(value);
    }
    return myInstanceLow.remove(value);
  }

  @Override
  public @NonNull IntIdsIterator intIterator() {
    if (myInstanceHigh != null) {
      return myInstanceHigh.intIterator();
    }
    return myInstanceLow.intIterator();
  }

  @Override
  public void compact() {
    if (myInstanceHigh != null) {
      myInstanceHigh.compact();
    } else {
      myInstanceLow.compact();
    }
  }

  @Override
  public int size() {
    if (myInstanceHigh != null) {
      return myInstanceHigh.size();
    }
    return myInstanceLow.size();
  }

  @Override
  public boolean contains(int value) {
    if (myInstanceHigh != null) {
      return myInstanceHigh.contains(value);
    }
    return myInstanceLow.contains(value);
  }

  @Override
  public @NonNull RandomAccessIntContainer ensureContainerCapacity(int diff) {
    int expectedSize = size() + diff;
    if (expectedSize >= myThreshold) {
      upgrade();
    }
    if (myInstanceHigh != null) {
      return myInstanceHigh.ensureContainerCapacity(diff);
    } else {
      return myInstanceLow.ensureContainerCapacity(diff);
    }
  }
}