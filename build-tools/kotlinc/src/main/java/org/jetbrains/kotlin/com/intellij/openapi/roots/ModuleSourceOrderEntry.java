package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

public interface ModuleSourceOrderEntry extends OrderEntry {
  @NonNull
  ModuleRootModel getRootModel();
}