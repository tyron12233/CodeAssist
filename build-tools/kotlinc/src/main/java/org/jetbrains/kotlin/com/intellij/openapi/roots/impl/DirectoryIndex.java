package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex;
import org.jetbrains.kotlin.com.intellij.openapi.roots.SourceFolder;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.Query;

import java.util.List;
import java.util.Set;

/**
 * This is internal class providing implementation for {@link ProjectFileIndex}.
 * It will be removed when all code switches to use the new implementation (IDEA-276394). 
 * All plugins which still use this class must be updated to use {@link ProjectFileIndex} and other APIs instead.
 */
@ApiStatus.Internal
@ApiStatus.Obsolete
public abstract class DirectoryIndex {
  public static DirectoryIndex getInstance(Project project) {
    // todo enable later when all usages will be fixed
    //assert !project.isDefault() : "Must not call DirectoryIndex for default project";
    return project.getService(DirectoryIndex.class);
  }

  @NotNull
  public abstract DirectoryInfo getInfoForFile(@NotNull VirtualFile file);

  @Nullable
  public abstract SourceFolder getSourceRootFolder(@NotNull DirectoryInfo info);

//  @Nullable
//  public abstract JpsModuleSourceRootType<?> getSourceRootType(@NotNull DirectoryInfo info);

  @NotNull
  public abstract Query<VirtualFile> getDirectoriesByPackageName(@NotNull String packageName, boolean includeLibrarySources);

  public Query<VirtualFile> getDirectoriesByPackageName(@NotNull String packageName, @NotNull GlobalSearchScope scope) {
    return getDirectoriesByPackageName(packageName, true).filtering(scope::contains);
  }

  @Nullable
  public abstract String getPackageName(@NotNull VirtualFile dir);

  @NotNull
  public abstract List<OrderEntry> getOrderEntries(@NotNull VirtualFile fileOrDir);

  /**
   * @return names of unloaded modules which directly or transitively via exported dependencies depend on the specified module
   */
  @NotNull
  public abstract Set<String> getDependentUnloadedModules(@NotNull Module module);
}