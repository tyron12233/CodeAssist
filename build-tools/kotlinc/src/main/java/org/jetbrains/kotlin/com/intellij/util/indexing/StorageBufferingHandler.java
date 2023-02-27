package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable;
import org.jetbrains.kotlin.com.intellij.util.indexing.UpdatableIndex;

import java.util.stream.Stream;

abstract class StorageBufferingHandler {
  private static final Logger LOG = Logger.getInstance(StorageBufferingHandler.class);
  private final StorageGuard myStorageLock = new StorageGuard();
  private volatile boolean myPreviousDataBufferingState;
  private final Object myBufferingStateUpdateLock = new Object();

  boolean runUpdate(boolean transientInMemoryIndices, @NotNull Computable<Boolean> update) {
    ProgressManager.checkCanceled();
    StorageGuard.StorageModeExitHandler storageModeExitHandler = myStorageLock.enter(transientInMemoryIndices);
    try {
      ensureBufferingState(transientInMemoryIndices);
      return update.compute();
    }
    finally {
      storageModeExitHandler.leave();
    }
  }

  private void ensureBufferingState(boolean transientInMemoryIndices) {
    if (myPreviousDataBufferingState != transientInMemoryIndices) {
      synchronized (myBufferingStateUpdateLock) {
        if (myPreviousDataBufferingState != transientInMemoryIndices) {
          getIndexes().forEach(index -> {
            try {
              index.setBufferingEnabled(transientInMemoryIndices);
            }
            catch (Exception e) {
              LOG.error(e);
            }
          });
          myPreviousDataBufferingState = transientInMemoryIndices;
        }
      }
    }
  }

  void resetState() {
    myPreviousDataBufferingState = false;
  }

  @NotNull
  protected abstract Stream<UpdatableIndex<?, ?, ?, ?>> getIndexes();
}