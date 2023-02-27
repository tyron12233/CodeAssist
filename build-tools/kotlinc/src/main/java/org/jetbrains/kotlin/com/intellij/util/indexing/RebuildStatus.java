package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.util.ThrowableRunnable;
import org.jetbrains.kotlin.com.intellij.util.TimeoutUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public enum RebuildStatus {
  OK,
  REQUIRES_REBUILD,
  DOING_REBUILD;

  private static final Map<ID<?, ?>, AtomicReference<RebuildStatus>> ourRebuildStatus = new HashMap<>();

  public static void registerIndex(ID<?, ?> indexId) {
    ourRebuildStatus.put(indexId, new AtomicReference<>(OK));
  }

  public static boolean isOk(ID<?, ?> indexId) {
    AtomicReference<RebuildStatus> rebuildStatus = ourRebuildStatus.get(indexId);
    return rebuildStatus != null && rebuildStatus.get() == OK;
  }

  static boolean requestRebuild(ID<?, ?> indexId) {
    return ourRebuildStatus.get(indexId).compareAndSet(OK, REQUIRES_REBUILD);
  }

  public static void clearIndexIfNecessary(ID<?, ?> indexId,
                                           ThrowableRunnable<StorageException> clearAction) throws StorageException {
    AtomicReference<RebuildStatus> rebuildStatus = ourRebuildStatus.get(indexId);
    if (rebuildStatus == null) {
      throw new StorageException("Problem updating " + indexId);
    }

    while (rebuildStatus.get() != OK) {
      if (rebuildStatus.compareAndSet(REQUIRES_REBUILD, DOING_REBUILD)) {
        try {
          clearAction.run();
          if (!rebuildStatus.compareAndSet(DOING_REBUILD, OK)) {
            throw new AssertionError("Unexpected status " + rebuildStatus.get());
          }
          continue;
        }
        catch (Throwable e) {
          rebuildStatus.compareAndSet(DOING_REBUILD, REQUIRES_REBUILD);
          throw e;
        }
      }

      ProgressManager.checkCanceled();
      TimeoutUtil.sleep(50);
    }
  }

  static boolean isOk() {
    return ourRebuildStatus.values().stream().map(AtomicReference::get).noneMatch(status -> status != OK);
  }

  static void reset() {
    ourRebuildStatus.clear();
  }

  public static @Nullable RebuildStatus getStatus(ID<?, ?> indexId) {
    AtomicReference<RebuildStatus> reference = ourRebuildStatus.get(indexId);
    return reference == null ? null : reference.get();
  }
}