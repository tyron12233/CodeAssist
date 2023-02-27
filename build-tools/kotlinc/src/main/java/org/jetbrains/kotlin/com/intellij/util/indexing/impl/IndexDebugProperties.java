package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexId;

public final class IndexDebugProperties {
  public static final ThreadLocal<IndexId<?, ?>> DEBUG_INDEX_ID = new ThreadLocal<>();

  @SuppressWarnings("StaticNonFinalField")
  public static volatile boolean DEBUG = SystemProperties.getBooleanProperty(
    "intellij.idea.indices.debug",
    false
  );

  public static volatile boolean IS_UNIT_TEST_MODE = true;

  public static volatile boolean IS_IN_STRESS_TESTS = false;

  public static final boolean EXTRA_SANITY_CHECKS = SystemProperties.getBooleanProperty(
    "intellij.idea.indices.debug.extra.sanity",
    false //DEBUG // todo https://youtrack.jetbrains.com/issue/IDEA-134916
  );
}