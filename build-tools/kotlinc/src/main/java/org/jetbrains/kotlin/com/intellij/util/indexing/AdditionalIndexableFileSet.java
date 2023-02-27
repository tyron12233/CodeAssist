package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValue;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValueProvider;
import org.jetbrains.kotlin.com.intellij.util.CachedValueImpl;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class AdditionalIndexableFileSet implements IndexableFileSet {
  @Nullable
  private final Project myProject;
  private final Supplier<IndexableSetContributor[]> myExtensions;

  private final CachedValue<AdditionalIndexableRoots> myAdditionalIndexableRoots;

  public AdditionalIndexableFileSet(@Nullable Project project, IndexableSetContributor @NotNull ... extensions) {
    myProject = project;
    myExtensions = () -> extensions;
    myAdditionalIndexableRoots = new CachedValueImpl<>(() -> new CachedValueProvider.Result<>(
            collectFilesAndDirectories(),
            VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS));
  }

  public AdditionalIndexableFileSet(@Nullable Project project) {
    myProject = project;
    myExtensions = IndexableSetContributor.EP_NAME::getExtensions;
    myAdditionalIndexableRoots = new CachedValueImpl<>(() -> new CachedValueProvider.Result<>(
            collectFilesAndDirectories(),
            VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            IndexableSetContributorModificationTracker.getInstance()));
  }

  @NotNull
  private AdditionalIndexableFileSet.AdditionalIndexableRoots collectFilesAndDirectories() {
    Set<VirtualFile> files = new HashSet<>();
    Set<VirtualFile> directories = new HashSet<>();
    for (IndexableSetContributor contributor : myExtensions.get()) {
      for (VirtualFile root : IndexableSetContributor.getRootsToIndex(contributor)) {
        if (root.isDirectory()) {
          directories.add(root);
        } else {
          files.add(root);
        }
      }
      if (myProject != null) {
        Set<VirtualFile> projectRoots = IndexableSetContributor.getProjectRootsToIndex(contributor, myProject);
        for (VirtualFile root : projectRoots) {
          if (root.isDirectory()) {
            directories.add(root);
          } else {
            files.add(root);
          }
        }
      }
    }
    return new AdditionalIndexableRoots(files, directories);
  }

  @Override
  public boolean isInSet(@NotNull VirtualFile file) {
    AdditionalIndexableRoots additionalIndexableRoots = myAdditionalIndexableRoots.getValue();
    return additionalIndexableRoots.files.contains(file) ||
           VfsUtilCore.isUnder(file, additionalIndexableRoots.directories);
  }

  private static class AdditionalIndexableRoots {
    private final Set<VirtualFile> files;
    private final Set<VirtualFile> directories;

    public AdditionalIndexableRoots(@NotNull Set<VirtualFile> files, @NotNull Set<VirtualFile> directories) {
      this.files = files;
      this.directories = directories;
    }

    public Set<VirtualFile> getFiles() {
      return files;
    }

    public Set<VirtualFile> getDirectories() {
      return directories;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof AdditionalIndexableRoots)) {
        return false;
      }

      AdditionalIndexableRoots that = (AdditionalIndexableRoots) o;

      if (!Objects.equals(files, that.files)) {
        return false;
      }
      return Objects.equals(directories, that.directories);
    }

    @Override
    public int hashCode() {
      int result = files.hashCode();
      result = 31 * result + directories.hashCode();
      return result;
    }
  }
}