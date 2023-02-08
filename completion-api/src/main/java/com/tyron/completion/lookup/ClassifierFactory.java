package com.tyron.completion.lookup;

import org.jetbrains.annotations.NonNls;

public abstract class ClassifierFactory<T> {
  private final String myId;

  protected ClassifierFactory(@NonNls String id) {
    myId = id;
  }

  public String getId() {
    return myId;
  }

  public abstract Classifier<T> createClassifier(Classifier<T> next);

  @SuppressWarnings("rawtypes")
  @Override
  public boolean equals(Object o) {
      if (this == o) {
          return true;
      }
      if (!(o instanceof ClassifierFactory)) {
          return false;
      }

      ClassifierFactory that = (ClassifierFactory) o;

      return myId.equals(that.myId);
  }

  @Override
  public int hashCode() {
    return myId.hashCode();
  }
}