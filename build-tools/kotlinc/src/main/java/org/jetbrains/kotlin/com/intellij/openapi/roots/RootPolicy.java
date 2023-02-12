package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

public class RootPolicy<R> {
  public R visitOrderEntry(@NonNull OrderEntry orderEntry, R value) {
    return value;
  }

  public R visitModuleSourceOrderEntry(@NonNull ModuleSourceOrderEntry moduleSourceOrderEntry, R value) {
    return visitOrderEntry(moduleSourceOrderEntry, value);
  }

  public R visitLibraryOrderEntry(@NonNull LibraryOrderEntry libraryOrderEntry, R value) {
    return visitOrderEntry(libraryOrderEntry, value);
  }

  public R visitModuleOrderEntry(@NonNull ModuleOrderEntry moduleOrderEntry, R value) {
    return visitOrderEntry(moduleOrderEntry, value);
  }

  public R visitJdkOrderEntry(@NonNull JdkOrderEntry jdkOrderEntry, R value) {
    return visitOrderEntry(jdkOrderEntry, value);
  }

  public R visitModuleJdkOrderEntry(@NonNull ModuleJdkOrderEntry jdkOrderEntry, R value) {
    return visitJdkOrderEntry(jdkOrderEntry, value);
  }

  public R visitInheritedJdkOrderEntry(@NonNull InheritedJdkOrderEntry inheritedJdkOrderEntry, R initialValue) {
    return visitJdkOrderEntry(inheritedJdkOrderEntry, initialValue);
  }
}