package org.jetbrains.kotlin.com.intellij.util.io;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;

final class IOStatistics {
  static final Logger LOG = Logger.getInstance(IOStatistics.class);

  static final boolean DEBUG = System.getProperty("io.access.debug") != null;

  /** Log time of storage flush operation, if it takes longer than ... */
  static final int MIN_IO_TIME_TO_REPORT_MS = 100;

  /**
   * Print enumerator statistics each time (valuesCount & KEYS_FACTOR_MASK) == 0
   *
   * @see PersistentBTreeEnumerator#enumerateImpl(Object, boolean, boolean)
   */
  static final int KEYS_FACTOR_MASK = 0xFFFF;

  static void dump(String msg) {
    LOG.info(msg);
  }

  private IOStatistics() { throw new AssertionError("Not for instantiation"); }
}