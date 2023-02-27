package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileFilter;

import java.util.Set;

/**
 * Provides information about files contained in a project or module.
 * In this interface and its inheritors, methods checking specific file status ("isX", "getX") should be used from a read action.
 * Iteration methods ("iterateX") may be called outside of a read action (since iteration can take a long time),
 * but they should be prepared to project model being changed in the middle of the iteration.
 *
 * @see ProjectRootManager#getFileIndex()
 * @see ModuleRootManager#getFileIndex()
 */
public interface FileIndex {
  /**
   * Processes all files and directories under content roots skipping excluded and ignored files and directories.
   *
   * @return false if files processing was stopped ({@link ContentIterator#processFile(VirtualFile)} returned false)
   */
  boolean iterateContent(@NonNull ContentIterator processor);

  /**
   * Same as {@link #iterateContent(ContentIterator)} but allows to pass {@code filter} to
   * provide filtering in condition for directories.
   * <p>
   * If {@code filter} returns false on a directory, the directory won't be processed, but iteration will go on.
   * <p>
   * {@code null} filter means that all directories should be processed.
   *
   * @return false if files processing was stopped ({@link ContentIterator#processFile(VirtualFile)} returned false)
   */
  boolean iterateContent(@NonNull ContentIterator processor, @Nullable VirtualFileFilter filter);

  /**
   * Processes all files and directories in the content under directory {@code dir} (including the directory itself) skipping excluded
   * and ignored files and directories. Does nothing if {@code dir} is not in the content and there's no content entries beneath.
   *
   * @return false if files processing was stopped in the middle of directory tree walking ({@link ContentIterator#processFile(VirtualFile)} returned false), true otherwise
   */
  boolean iterateContentUnderDirectory(@NonNull VirtualFile dir, @NonNull ContentIterator processor);

  /**
   * Same as {@link #iterateContentUnderDirectory(VirtualFile, ContentIterator)} but allows to pass additional {@code customFilter} to
   * the iterator, in case you need to skip some file system branches using your own logic. If {@code customFilter} returns false on
   * a directory, it won't be processed, but iteration will go on.
   * <p>
   * {@code null} filter means that all directories should be processed.
   */
  boolean iterateContentUnderDirectory(@NonNull VirtualFile dir,
                                       @NonNull ContentIterator processor,
                                       @Nullable VirtualFileFilter customFilter);

  /**
   * Returns {@code true} if {@code fileOrDir} is a file or directory under a content root of this project or module and not excluded or
   * ignored.
   */
  boolean isInContent(@NonNull VirtualFile fileOrDir);

  /**
   * Returns {@code true} if {@code fileOrDir} is a file or directory located under a source root of some module and not excluded or ignored.
   */
  boolean isInSourceContent(@NonNull VirtualFile fileOrDir);

  /**
   * Returns {@code true} if {@code fileOrDir} is a file or directory located under a test sources or resources root and not excluded or ignored.
   * <p>
   * Use this method when you really need to check whether the file is under test roots according to project configuration.
   * <p>
   * If you want to determine whether file should be considered as test (e.g. for implementing SearchScope)
   * you'd better use {@link TestSourcesFilter#isTestSources(VirtualFile, Project)} instead
   * which calls this method for you.
   * @see TestSourcesFilter#isTestSources(VirtualFile, Project)
   */
  boolean isInTestSourceContent(@NonNull VirtualFile fileOrDir);
//
//  /**
//   * Returns {@code true} if {@code fileOrDir} is a file or directory located under a source root of type from {@code rootTypes} set and not excluded or ignored
//   */
//  boolean isUnderSourceRootOfType(@NonNull VirtualFile fileOrDir, @NonNull Set<? extends JpsModuleSourceRootType<?>> rootTypes);
}