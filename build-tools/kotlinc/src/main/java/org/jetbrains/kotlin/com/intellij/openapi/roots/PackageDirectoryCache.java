package org.jetbrains.kotlin.com.intellij.openapi.roots;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.PackageDirectoryCacheImpl;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;

import java.util.List;
import java.util.Set;

/**
 * Provides a fast way to retrieve information about packages corresponding to nested directories when root directories are given.
 */
public interface PackageDirectoryCache {
  @NotNull List<VirtualFile> getDirectoriesByPackageName(@NotNull String packageName);

  @NotNull Set<String> getSubpackageNames(@NotNull String packageName, @NotNull GlobalSearchScope scope);

  static @NotNull PackageDirectoryCache createCache(@NotNull List<? extends VirtualFile> roots) {
    return new PackageDirectoryCacheImpl((packageName, result) -> {
      if (packageName.isEmpty()) {
        PackageDirectoryCacheImpl.addValidDirectories(roots, result);
      }
    }, (dir, name) -> true);
  }
}