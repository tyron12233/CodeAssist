package org.jetbrains.kotlin.com.intellij.util.io;

public interface MeasurableIndexStore {
  int KEYS_COUNT_UNKNOWN = -1;

  /**
   * Method was introduced for analytics purposes, and for that it is not required to be precise,
   * i.e. it is OK to provide estimations, outdated info, include keys just removed, or something like
   * that -- but it should be fast (ideally O(1), but at least sublinear on size).
   * <p>
   * It could be hard/costly to implement this method precisely for data structures with layered caching,
   * and it is not clear would the method be useful in other contexts there precision is important,
   * is it worth to define it as precise, and take associated costs.
   *
   * @return approximated number of keys in index, or -1 if this index doesn't provide such information
   */
  int keysCountApproximately();

  static int keysCountApproximatelyIfPossible(final Object o) {
    if (o instanceof MeasurableIndexStore) {
      return ((MeasurableIndexStore)o).keysCountApproximately();
    }
    return KEYS_COUNT_UNKNOWN;
  }
}