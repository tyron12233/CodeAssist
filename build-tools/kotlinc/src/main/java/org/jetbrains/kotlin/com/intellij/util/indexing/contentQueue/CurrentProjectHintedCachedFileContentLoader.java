package org.jetbrains.kotlin.com.intellij.util.indexing.contentQueue;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.project.ProjectLocator;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileTooBigException;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VFileProperty;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public class CurrentProjectHintedCachedFileContentLoader implements CachedFileContentLoader {
  private final Project myProject;

  public CurrentProjectHintedCachedFileContentLoader(Project project) {
    myProject = project;
  }

  @Override
  @NotNull
  public CachedFileContent loadContent(@NotNull VirtualFile file) throws FailedToLoadContentException, TooLargeContentException {
    CachedFileContent content = new CachedFileContent(file);
    if (file.isDirectory() || !file.isValid() || file.is(VFileProperty.SPECIAL)) {
      content.setEmptyContent();
      return content;
    }

    // Reads the content bytes and caches them. Hint at the current project to avoid expensive read action in ProjectLocator.
    try {
      ProjectLocator.computeWithPreferredProject(file, myProject, content::getBytes);
    }
    catch (ProcessCanceledException e) {
      throw e;
    }
    catch (FileTooBigException e) {
      throw new TooLargeContentException(file);
    }
    catch (Throwable e) {
      throw new FailedToLoadContentException(file, e);
    }
    return content;
  }
}