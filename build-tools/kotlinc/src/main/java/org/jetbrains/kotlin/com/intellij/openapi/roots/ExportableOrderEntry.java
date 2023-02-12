package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

public interface ExportableOrderEntry extends OrderEntry {
  boolean isExported();

  /**
   * Updates 'exported' flag for the entry. This method may be called only on a modifiable instance obtained from {@link ModifiableRootModel}.
   */
  void setExported(boolean value);

  @NonNull
  DependencyScope getScope();

  /**
   * Updates scope for the entry. This method may be called only on a modifiable instance obtained from {@link ModifiableRootModel}.
   */
  void setScope(@NonNull DependencyScope scope);
}