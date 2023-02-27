package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;

public final class AdditionalIndexedRootsScope extends GlobalSearchScope {
  private final GlobalSearchScope myBaseScope;
  @NotNull
  private final IndexableFileSet myFileSet;

  public AdditionalIndexedRootsScope(@NotNull GlobalSearchScope baseScope) {
    this(baseScope, new AdditionalIndexableFileSet(null));
  }

  public AdditionalIndexedRootsScope(@NotNull GlobalSearchScope baseScope, @NotNull Class<? extends IndexableSetContributor> providerClass) {
    this(baseScope, new AdditionalIndexableFileSet(null, IndexableSetContributor.EP_NAME.findExtension(providerClass)));
  }

  public AdditionalIndexedRootsScope(@NotNull GlobalSearchScope baseScope, @NotNull IndexableFileSet myFileSet) {
    super(baseScope.getProject());
    myBaseScope = baseScope;
    this.myFileSet = myFileSet;
  }

  @Override
  public boolean contains(@NotNull VirtualFile file) {
    return myBaseScope.contains(file) || myFileSet.isInSet(file);
  }

  @Override
  public int compare(@NotNull VirtualFile file1, @NotNull VirtualFile file2) {
    return myBaseScope.compare(file1, file2);
  }

  @Override
  public boolean isSearchInModuleContent(@NotNull Module aModule) {
    return myBaseScope.isSearchInModuleContent(aModule);
  }

  @Override
  public boolean isSearchInLibraries() {
    return myBaseScope.isSearchInLibraries();
  }
}