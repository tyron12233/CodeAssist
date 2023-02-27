package org.jetbrains.kotlin.com.intellij.util.indexing.containers;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;

import java.util.HashSet;
import java.util.Iterator;

public class IntHashSetAsRAIntContainer implements RandomAccessIntContainer {
  private HashSet<Integer> myHashSet; // todo: fastutil.StrippedIntOpenHashSet?

  public IntHashSetAsRAIntContainer(int initialCapacity, float loadFactor) {
    myHashSet = new HashSet<>(initialCapacity, loadFactor);
  }

  @SuppressWarnings("unchecked")
  @Override
  public Object clone() {
    try {
      IntHashSetAsRAIntContainer copy = (IntHashSetAsRAIntContainer)super.clone();
      copy.myHashSet = (HashSet<Integer>)myHashSet.clone();
      return copy;
    } catch (CloneNotSupportedException e) {
      Logger.getInstance(getClass().getName()).error(e);
      return null;
    }
  }

  @Override
  public boolean add(int value) {
    return myHashSet.add(value);
  }

  @Override
  public boolean remove(int value) {
    return myHashSet.remove(value);
  }

  @Override
  public @NonNull IntIdsIterator intIterator() {
    return new MyIterator();
  }

  @Override
  public void compact() {}

  @Override
  public int size() {
    return myHashSet.size();
  }

  @Override
  public boolean contains(int value) {
    return myHashSet.contains(value);
  }

  @Override
  public @NonNull RandomAccessIntContainer ensureContainerCapacity(int diff) {
    return this;
  }

  private class MyIterator implements IntIdsIterator {
    private final Iterator<Integer> myHashSetIterator;

    private MyIterator() {
      myHashSetIterator = myHashSet.iterator();
    }

    @Override
    public boolean hasNext() {
      return myHashSetIterator.hasNext();
    }

    @Override
    public int next() {
      return myHashSetIterator.next();
    }

    @Override
    public int size() {
      return myHashSet.size();
    }

    @Override
    public boolean hasAscendingOrder() {
      return false;
    }

    @Override
    public IntIdsIterator createCopyInInitialState() {
      return new MyIterator();
    }
  }
}