package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.roots.libraries.Library;

public interface LibraryOrderEntry extends LibraryOrSdkOrderEntry, ExportableOrderEntry {
  @Nullable
  Library getLibrary();
  
  boolean isModuleLevel();

  String getLibraryLevel();

  @Nullable
  String getLibraryName();
}