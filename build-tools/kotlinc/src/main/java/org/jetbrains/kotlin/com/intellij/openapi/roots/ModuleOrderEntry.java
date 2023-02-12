package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;

public interface ModuleOrderEntry extends ExportableOrderEntry {
  @Nullable
  Module getModule();

  @NonNull
  String getModuleName();

  /**
   * If {@code true} test sources roots from the dependency will be included into production classpath for the module containing this entry.
   */
  boolean isProductionOnTestDependency();

  void setProductionOnTestDependency(boolean productionOnTestDependency);
}