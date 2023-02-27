package org.jetbrains.kotlin.com.intellij.util.indexing.roots;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.util.List;
import java.util.function.Predicate;

/**
 * A base interface to provide a files which should be indexed for a given project.
 */
//@ApiStatus.Experimental
//@ApiStatus.OverrideOnly
public interface IndexableFilesContributor {
  ExtensionPointName<IndexableFilesContributor> EP_NAME = ExtensionPointName.create("com.intellij.indexableFilesContributor");

  /**
   * Returns ordered list of logical file sets (module files, SDK files, etc) to be indexed. Note:
   * <ul>
   * <li>The method is called in read-action with valid {@param project}.</li>
   * <li>{@link IndexableFilesIterator}-s will be indexed in provided order.</li>
   * <li>Files in {@link IndexableFilesIterator} should be not evaluated eagerly for performance reasons.</li>
   * </ul>
   */
  @NonNull
  List<IndexableFilesIterator> getIndexableFiles(@NonNull Project project);

  /**
   * Quickly should answer does file belongs to files contributor.
   * Used to filter out file events which is required to update indexes.
   */
  @NonNull
  Predicate<VirtualFile> getOwnFilePredicate(@NonNull Project project);
}