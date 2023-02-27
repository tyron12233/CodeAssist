package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.diagnostic.PluginException;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Represents a set of files which should be indexed additionally to a default ones.
 * <br>
 * Files provided by {@link IndexableSetContributor} will be indexed (or ensured up to date) on project loading and
 * {@link FileBasedIndex} automatically rebuilds indexes for these files when they are going to be changed.
 */
//@ApiStatus.OverrideOnly
public abstract class IndexableSetContributor {

  public static final ExtensionPointName<IndexableSetContributor> EP_NAME = new ExtensionPointName<>("com.intellij.indexedRootsProvider");
  private static final Logger LOG = Logger.getInstance(IndexableSetContributor.class);

  @NonNull
  public static Set<VirtualFile> getProjectRootsToIndex(@NonNull IndexableSetContributor contributor, @NonNull Project project) {
    Set<VirtualFile> roots = contributor.getAdditionalProjectRootsToIndex(project);
    return filterOutNulls(contributor, "getAdditionalProjectRootsToIndex(Project)", roots);
  }

  @NonNull
  public static Set<VirtualFile> getRootsToIndex(@NonNull IndexableSetContributor contributor) {
    Set<VirtualFile> roots = contributor.getAdditionalRootsToIndex();
    return filterOutNulls(contributor, "getAdditionalRootsToIndex()", roots);
  }

  public boolean acceptFile(@NonNull VirtualFile file, @NonNull VirtualFile root, @Nullable Project project) {
    return true;
  }

  /**
   * @return an additional project-dependent set of {@link VirtualFile} instances to index,
   *         the returned set should not contain {@code null} files or invalid files.
   */
  @NonNull
  public Set<VirtualFile> getAdditionalProjectRootsToIndex(@NonNull Project project) {
    return Collections.emptySet();
  }

  /**
   * @return an additional project-independent set of {@link VirtualFile} instances to index,
   *         the returned set should not contain {@code null} files or invalid files.
   */
  @NonNull
  public abstract Set<VirtualFile> getAdditionalRootsToIndex();

  /**
   * @return contributor's debug name for indexing diagnostic report.
   */
  @NonNull
  public String getDebugName() {
    return toString();
  }

  @NonNull
  private static Set<VirtualFile> filterOutNulls(@NonNull IndexableSetContributor contributor,
                                                 @NonNull String methodInfo,
                                                 @NonNull Set<VirtualFile> roots) {
    for (VirtualFile root : roots) {
      if (root == null || !root.isValid()) {
        LOG.error(PluginException.createByClass("Please fix " + contributor.getClass().getName() + "#" + methodInfo + ".\n" +
                                                (root == null ? "The returned set is not expected to contain nulls, but it is " + roots
                                                              : "Invalid file returned: " + root), null, contributor.getClass()));
        return new LinkedHashSet<>(ContainerUtil.filter(roots, virtualFile -> virtualFile != null && virtualFile.isValid()));
      }
    }
    return roots;
  }
}