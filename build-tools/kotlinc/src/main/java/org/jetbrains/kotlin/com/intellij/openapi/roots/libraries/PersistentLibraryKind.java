package org.jetbrains.kotlin.com.intellij.openapi.roots.libraries;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType;

public abstract class PersistentLibraryKind<P extends LibraryProperties> extends LibraryKind {
  /**
   * @param kindId must be unique among all {@link com.intellij.openapi.roots.libraries.LibraryType} and
   *               {@link com.intellij.openapi.roots.libraries.LibraryPresentationProvider} implementations.
   */
  public PersistentLibraryKind(@NonNull String kindId) {
    super(kindId);
  }

  @NonNull
  public abstract P createDefaultProperties();

  public OrderRootType [] getAdditionalRootTypes() {
    return new OrderRootType[0];
  }
}