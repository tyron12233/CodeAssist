package org.jetbrains.kotlin.com.intellij.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class FlushingDaemon {
  public static final String NAME = "Flushing Daemon";

  private FlushingDaemon() {}

  @NotNull
  public static ScheduledFuture<?> everyFiveSeconds(@NotNull Runnable r) {
    return AppExecutorUtil
      .getAppScheduledExecutorService()
      .scheduleWithFixedDelay(ConcurrencyUtil.underThreadNameRunnable(NAME, r), 5, 5, TimeUnit.SECONDS);
  }
}