package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;

public interface RootModelProvider {
  Module[] getModules();

  ModuleRootModel getRootModel(@NonNull Module module);
}