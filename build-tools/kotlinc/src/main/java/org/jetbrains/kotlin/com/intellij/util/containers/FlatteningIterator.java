package org.jetbrains.kotlin.com.intellij.util.containers;

import java.util.Collections;
import java.util.Iterator;

public abstract class FlatteningIterator<Group, Value> implements Iterator<Value> {
  private final Iterator<? extends Group> valuesIterator;
  private Iterator<Value> groupIterator;
  private Boolean hasNextCache;

  public FlatteningIterator(Iterator<? extends Group> groups) {
    valuesIterator = groups;
    groupIterator = Collections.emptyIterator();
  }

  @Override
  public boolean hasNext() {
    if (hasNextCache != null) {
      return hasNextCache.booleanValue();
    }

    while (!groupIterator.hasNext() && valuesIterator.hasNext()) {
      groupIterator = createValueIterator(valuesIterator.next());
    }
    return hasNextCache = groupIterator.hasNext();
  }

  protected abstract Iterator<Value> createValueIterator(Group group);

  @Override
  public Value next() {
    if (!hasNext()) {
      throw new AssertionError();
    }
    hasNextCache = null;
    return groupIterator.next();
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }
}