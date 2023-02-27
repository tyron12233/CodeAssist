package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.ModalityState;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileEvent;

import java.util.Collection;
import java.util.List;

public abstract class RefreshQueue {
  public static RefreshQueue getInstance() {
    return ApplicationManager.getApplication().getService(RefreshQueue.class);
  }

  public final @NonNull RefreshSession createSession(boolean async, boolean recursive, @Nullable Runnable finishRunnable) {
    return createSession(async, recursive, finishRunnable, ModalityState.defaultModalityState());
  }

  public abstract @NonNull RefreshSession createSession(boolean async, boolean recursive, @Nullable Runnable finishRunnable, @NonNull ModalityState state);

  public final void refresh(boolean async, boolean recursive, @Nullable Runnable finishRunnable, @NonNull VirtualFile ... files) {
    refresh(async, recursive, finishRunnable, ModalityState.defaultModalityState(), files);
  }

  public final void refresh(boolean async, boolean recursive, @Nullable Runnable finishRunnable, @NonNull Collection<? extends VirtualFile> files) {
    refresh(async, recursive, finishRunnable, ModalityState.defaultModalityState(), files);
  }

  public final void refresh(boolean async,
                            boolean recursive,
                            @Nullable Runnable finishRunnable,
                            @NonNull ModalityState state,
                            @NonNull VirtualFile... files) {
    RefreshSession session = createSession(async, recursive, finishRunnable, state);
    session.addAllFiles(files);
    session.launch();
  }

  public final void refresh(boolean async,
                            boolean recursive,
                            @Nullable Runnable finishRunnable,
                            @NonNull ModalityState state,
                            @NonNull Collection<? extends VirtualFile> files) {
    RefreshSession session = createSession(async, recursive, finishRunnable, state);
    session.addAllFiles(files);
    session.launch();
  }

//  @ApiStatus.Internal
  public abstract void processEvents(boolean async, @NonNull List<? extends VFileEvent> events);

  public abstract void cancelSession(long id);
}