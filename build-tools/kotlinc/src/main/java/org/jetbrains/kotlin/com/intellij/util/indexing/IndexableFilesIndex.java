package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.indexing.roots.IndexableFilesIterator;

import java.util.Collection;
import java.util.List;

public interface IndexableFilesIndex {
  @VisibleForTesting
  Key<Boolean> ENABLE_IN_TESTS = new Key<>("enable.IndexableFilesIndex");

  /**
   * See {@link com.intellij.util.indexing.roots.StandardContributorsKt#shouldIndexProjectBasedOnIndexableEntityProviders()}
   */
  static boolean isEnabled() {
   return (Registry.is("indexing.use.indexable.files.index") ||
//           (ApplicationManager.getApplication().isUnitTestMode() && TestModeFlags.is(ENABLE_IN_TESTS))) &&
//          WorkspaceFileIndexEx.IS_ENABLED &&
          Registry.is("indexing.enable.entity.provider.based.indexing"));
  }

  @NotNull
  static IndexableFilesIndex getInstance(@NotNull Project project) {
    assert isEnabled();
    return project.getService(IndexableFilesIndex.class);
  }

//  @RequiresBackgroundThread
  boolean shouldBeIndexed(@NotNull VirtualFile file);

//  @RequiresBackgroundThread
  @NotNull
  List<IndexableFilesIterator> getIndexingIterators();

//  @RequiresBackgroundThread
  @NotNull
    Collection<IndexableFilesIterator> getModuleIndexingIterators(@NotNull Module entity);
}