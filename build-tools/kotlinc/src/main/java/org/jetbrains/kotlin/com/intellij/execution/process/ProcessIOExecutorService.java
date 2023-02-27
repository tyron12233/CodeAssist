package org.jetbrains.kotlin.com.intellij.execution.process;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.kotlin.com.intellij.util.concurrency.CountingThreadFactory;

import java.util.concurrent.*;

/**
 * A thread pool for long-running workers needed for handling child processes or network requests,
 * to avoid occupying workers in the main application pool and constantly creating new threads there.
 */
public final class ProcessIOExecutorService extends ThreadPoolExecutor {
  public static final @NonNls String POOLED_THREAD_PREFIX = "I/O pool ";
  public static final ExecutorService INSTANCE = new ProcessIOExecutorService();

  private ProcessIOExecutorService() {
    super(1, Integer.MAX_VALUE, 1, TimeUnit.MINUTES, new SynchronousQueue<>(), new MyCountingThreadFactory());
  }

  @TestOnly
  public int getThreadCounter() {
    return ((MyCountingThreadFactory)getThreadFactory()).getCount();
  }

  private static final class MyCountingThreadFactory extends CountingThreadFactory {
    // Ensure that we don't keep the classloader of the plugin which caused this thread to be created
    // in Thread.inheritedAccessControlContext
    private final ThreadFactory myThreadFactory = Executors.privilegedThreadFactory();

    @NotNull
    @Override
    public Thread newThread(@NotNull final Runnable r) {
      Thread thread = myThreadFactory.newThread(r);
      thread.setName(POOLED_THREAD_PREFIX + counter.incrementAndGet());
      thread.setPriority(Thread.NORM_PRIORITY - 1);
      return thread;
    }

    public int getCount() {
      return counter.get();
    }
  }
}