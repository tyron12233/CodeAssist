package com.tyron.completion.resolve;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.SearchScope;

/**
 * This extension point allows resolve subsystem to modify existing resolve scope for the particular {@link VirtualFile} by specifying
 * {@link SearchScope} which should be added to the existing resolve scope.
 * For example, {@link com.intellij.ide.scratch.ScratchResolveScopeEnlarger} adds current scratch file to the standard resolve scope
 * to be able to resolve stuff inside scratch file even if it's outside the project roots.
 */
public abstract class ResolveScopeEnlarger {
  public static final ExtensionPointName<ResolveScopeEnlarger> EP_NAME = ExtensionPointName.create("com.intellij.resolveScopeEnlarger");

  @Nullable
  public abstract SearchScope getAdditionalResolveScope(@NotNull VirtualFile file, @NotNull Project project);
}