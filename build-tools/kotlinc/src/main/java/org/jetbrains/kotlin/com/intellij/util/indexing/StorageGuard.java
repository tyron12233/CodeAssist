package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.util.ProgressIndicatorUtils;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

final class StorageGuard {
  private final Lock myLock = new ReentrantLock();
  private final Condition myCondition = myLock.newCondition();

  private int myHolds;

  public interface StorageModeExitHandler {
    void leave();
  }

  private final StorageModeExitHandler myTrueStorageModeExitHandler = new StorageModeExitHandler() {
    @Override
    public void leave() {
      StorageGuard.this.leave(true);
    }
  };
  private final StorageModeExitHandler myFalseStorageModeExitHandler = new StorageModeExitHandler() {
    @Override
    public void leave() {
      StorageGuard.this.leave(false);
    }
  };

  @SuppressWarnings("WhileLoopSpinsOnField")
  @NotNull
  StorageModeExitHandler enter(boolean mode) {
    boolean nonCancelableSection = ProgressManager.getInstance().isInNonCancelableSection();
    myLock.lock();
    try {
      if (mode) {
        while (myHolds < 0) {
          await(nonCancelableSection);
        }
        myHolds++;
        return myTrueStorageModeExitHandler;
      }
      else {
        while (myHolds > 0) {
          await(nonCancelableSection);
        }
        myHolds--;
        return myFalseStorageModeExitHandler;
      }
    }
    finally {
      myLock.unlock();
    }
  }

  private void await(boolean nonCancelableSection) {
    if (nonCancelableSection) {
      myCondition.awaitUninterruptibly();
    }
    else {
      ProgressIndicatorUtils.awaitWithCheckCanceled(myCondition);
    }
  }

  private void leave(boolean mode) {
    myLock.lock();
    try {
      myHolds += mode ? -1 : 1;
      if (myHolds == 0) {
        myCondition.signalAll();
      }
    }
    finally {
      myLock.unlock();
    }
  }
}