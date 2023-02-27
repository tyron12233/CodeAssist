package org.jetbrains.kotlin.com.intellij.openapi.roots;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

/**
 * Implementations of this extension point can tell IDE whether some particular file is a test file.
 * <p>
 * By default, IntelliJ Platform considers files as tests only if they are located under test
 * sources root {@link FileIndex#isInTestSourceContent(VirtualFile)}.
 * <p>
 * However there are plenty frameworks and languages which keep test files just nearby production files.
 * E.g. *_test.go files are test files in Go language and some js/dart files are test files depending
 * on their content. The extensions allow IDE to highlight such files with a green background,
 * properly check if they are included in built-in search scopes, etc.
 *
 * @see FileIndex#isInTestSourceContent(VirtualFile)
 * @see JpsModuleSourceRootType#isForTests()
 * @author zolotov
 */
public abstract class TestSourcesFilter {
  private static final ExtensionPointName<TestSourcesFilter> EP_NAME = ExtensionPointName.create("com.intellij.testSourcesFilter");

  public static boolean isTestSources(@NotNull VirtualFile file, @NotNull Project project) {
    for (TestSourcesFilter filter : EP_NAME.getExtensions()) {
      if (filter.isTestSource(file, project)) {
        return true;
      }
    }
    return false;
  }

  @ApiStatus.OverrideOnly
  public abstract boolean isTestSource(@NotNull VirtualFile file, @NotNull Project project);
}
