package com.tyron.completion.resolve;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;

/**
 * This extension point allows resolve subsystem to define custom {@link GlobalSearchScope} this particular {@link VirtualFile} should be resolved in.
 * By default, this scope consists of the current module with all its dependencies, but sometimes it should be something completely different.
 * To add some scope to the existing resolve scope it may be easier to use {@link ResolveScopeEnlarger} instead.
 * @see ResolveScopeEnlarger
 */
public abstract class ResolveScopeProvider {
  public static final ExtensionPointName<ResolveScopeProvider> EP_NAME = ExtensionPointName.create("com.intellij.resolveScopeProvider");

  /**
   * @return {@link GlobalSearchScope} which defines destination scope where to resolve is allowed from given {@code invocationPoint}.
   */
  @Nullable
  public abstract GlobalSearchScope getResolveScope(@NotNull VirtualFile file, @NotNull Project project);
}