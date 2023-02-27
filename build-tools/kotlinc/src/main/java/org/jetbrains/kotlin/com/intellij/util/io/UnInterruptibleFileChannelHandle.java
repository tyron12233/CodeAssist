package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A class intended to overcome interruptibility of {@link FileChannel}
 * by repeating passed operation until it will be successfully applied.
 *
 * If underlying {@link FileChannel} is observed in closed by interruption state,
 * in other words {@link ClosedByInterruptException} has been thrown we're trying to reopen it and apply operation again.
 */
final class UnInterruptibleFileChannelHandle {
  private static final Logger LOG = Logger.getInstance(UnInterruptibleFileChannelHandle.class);

  private final @NonNull Lock myOpenCloseLock = new ReentrantLock();
  private final @NonNull Path myPath;
  private final Set<? extends OpenOption> myOpenOptions;

  private volatile FileChannel myChannel; // null if handle has been closed

  UnInterruptibleFileChannelHandle(@NonNull Path path, Set<? extends OpenOption> openOptions) throws IOException {
    myPath = path;
    myOpenOptions = openOptions;
    reopenChannel();
  }

  <T> T executeOperation(@NonNull FileChannelIdempotentOperation<T> operation) throws IOException {
    if (!isOpen()) {
      throw new ClosedChannelException();
    }
    boolean restoreInterruption = false;
    try {
      while (true) {
        try {
          return operation.execute(myChannel);
        }
        catch (ClosedChannelException e) {
          LOG.warn("Channel " + System.identityHashCode(myChannel) + " for " + myPath + " closed. Trying to reopen it again");
          if (Thread.currentThread().isInterrupted()) {
            Thread.interrupted();
            restoreInterruption = true;
          }
          reopenChannel();
        }
      }
    }
    finally {
      if (restoreInterruption) {
        Thread.currentThread().interrupt();
      }
    }
  }

  boolean isOpen() {
    return myChannel != null;
  }

  void close() throws IOException {
    myOpenCloseLock.lock();
      try {
tryClose();
      } finally {
        myOpenCloseLock.unlock();
      }

  }

  void reopenChannel() throws IOException {
    myOpenCloseLock.lock();
    try {
      FileChannel newFileChannel = FileChannel.open(myPath, myOpenOptions);
      try {
        tryClose();
      }
      catch (IOException ignored) {
      }
      myChannel = newFileChannel;
    } finally {
      myOpenCloseLock.unlock();
    }
  }

  private void tryClose() throws IOException {
    try {
      FileChannel channel = myChannel;
      if (channel != null && channel.isOpen()) {
        channel.close();
      }
    }
    finally {
      myChannel = null;
    }
  }

  interface FileChannelIdempotentOperation<T> {
    T execute(@NonNull FileChannel fileChannel) throws IOException;
  }
}