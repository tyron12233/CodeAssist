package org.jetbrains.kotlin.com.intellij.openapi.project;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;

/**
 * A task that should be executed in IDE dumb mode, via {@link DumbService#queueTask(DumbModeTask)}.
 */
public abstract class DumbModeTask implements Disposable, MergeableQueueTask<DumbModeTask> {
  @Nullable
  private final Object myEquivalenceObject;

  /**
   * Consider implementing {@link DumbModeTask#tryMergeWith(DumbModeTask)} to allow alike tasks to merge while waiting in queue
   */
  public DumbModeTask() {
    myEquivalenceObject = null;
  }

  /**
   * Tasks with same class and {@code equivalenceObject} would be merged while waiting in queue
   * unless {@link DumbModeTask#tryMergeWith(DumbModeTask)} is overwritten.
   *
   * @deprecated Consider using {@link DumbModeTask()} and overwriting {@link DumbModeTask#tryMergeWith(DumbModeTask)} instead.
   */
  @ApiStatus.ScheduledForRemoval
  @Deprecated
  public DumbModeTask(@NotNull Object equivalenceObject) {
    myEquivalenceObject = Pair.create(getClass(), equivalenceObject);
  }

  public abstract void performInDumbMode(@NotNull ProgressIndicator indicator);

  @Override
  public final void perform(@NotNull ProgressIndicator indicator) {
    performInDumbMode(indicator);
  }

  @Override
  public void dispose() {
  }

  /**
   * Allows merging tasks waiting in queue for execution.
   *
   * @return {@code null} - if current task has nothing to do with {@code taskFromQueue}; <p>
   *         {@code this} - if you want to remove {@code taskFromQueue} from the queue and add current one;  <p>
   *         some other task - then it would be added to the queue, and {@code taskFromQueue} would be removed.
   */
  @Override
  @Nullable
  public DumbModeTask tryMergeWith(@NotNull DumbModeTask taskFromQueue) {
    if (myEquivalenceObject != null && myEquivalenceObject.equals(taskFromQueue.myEquivalenceObject)) {
      return this;
    }
    return null;
  }

  /**
   * Queues dumb mode task to be performed in dumb mode. See {@link DumbService#queueTask(DumbModeTask)}.
   */
  public final void queue(@NotNull Project project) {
//    DumbService.getInstance(project).queueTask(this);
    Logger.getInstance(this.getClass()).warn("DumbService.queueTask not properly implemented.");

    this.performInDumbMode(new EmptyProgressIndicator());
  }
}