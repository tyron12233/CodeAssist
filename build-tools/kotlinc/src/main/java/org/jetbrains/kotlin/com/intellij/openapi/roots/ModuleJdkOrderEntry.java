package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.Nullable;

public interface ModuleJdkOrderEntry extends JdkOrderEntry {
  @Nullable
  String getJdkTypeName();
}