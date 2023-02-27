package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.impl;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.PlainTextFileType;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.BulkFileListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.kotlin.com.intellij.util.FileContentUtilCore;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@ApiStatus.Internal
public final class CachedFileType {
  private static final ConcurrentMap<FileType, CachedFileType> ourInterner = new ConcurrentHashMap<>();

  private @Nullable FileType fileType;

  private CachedFileType(@NotNull FileType fileType) {
    this.fileType = fileType;
  }

  @Nullable FileType getUpToDateOrNull() {
    return fileType;
  }

  static CachedFileType forType(@NotNull FileType fileType) {
    ApplicationManager.getApplication().assertReadAccessAllowed();
    return ourInterner.computeIfAbsent(fileType, CachedFileType::new);
  }

  public static void clearCache() {
    ApplicationManager.getApplication().assertWriteAccessAllowed();
    ourInterner.forEach((type, cachedType) -> {
      // clear references to file types to aid plugin unloading
      cachedType.fileType = null;
    });
    ourInterner.clear();
  }

  /**
   * @return result that returns true if no files changed their types since method invocation
   */
  @NotNull
  public static Supplier<@NotNull Boolean> getFileTypeChangeChecker() {
    CachedFileType type = ReadAction.compute(() -> forType(PlainTextFileType.INSTANCE));
    return () -> {
      return type.getUpToDateOrNull() != null;
    };
  }

  static final class PsiListener implements PsiModificationTracker.Listener {
    @Override
    public void modificationCountChanged() {
      clearCache();
    }
  }

  static final class ReparseListener implements BulkFileListener {
    @Override
    public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
      for (VFileEvent event : events) {
//        if (FileContentUtilCore.FORCE_RELOAD_REQUESTOR.equals(event.getRequestor())) {
//          clearCache();
//          break;
//        }
        Logger.getInstance(ReparseListener.class).warn("Unimplemented reparse listener");
      }
    }
  }
}