package org.jetbrains.kotlin.com.intellij.psi.search.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;

import java.util.Collection;

/**
 * An internal interface to perform index search optimization based on scope.
 * It represents file enumeration which contains whole {@link GlobalSearchScope}
 *
 * You definitely don't need to use it.
 */
public interface VirtualFileEnumeration {
  boolean contains(int fileId);

  int[] asArray();

  default @Nullable Collection<VirtualFile> getFilesIfCollection() {
    return null;
  }

  static @Nullable VirtualFileEnumeration extract(@NonNull GlobalSearchScope scope) {
    if (scope instanceof VirtualFileEnumeration) {
      return (VirtualFileEnumeration)scope;
    }
    if (scope instanceof VirtualFileEnumerationAware) {
      return ((VirtualFileEnumerationAware)scope).extractFileEnumeration();
    }
    return null;
  }
}