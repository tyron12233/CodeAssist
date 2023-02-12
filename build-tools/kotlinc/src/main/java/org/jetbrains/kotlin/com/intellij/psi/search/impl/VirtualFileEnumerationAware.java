package org.jetbrains.kotlin.com.intellij.psi.search.impl;

import androidx.annotation.Nullable;

public interface VirtualFileEnumerationAware {
  @Nullable
  VirtualFileEnumeration extractFileEnumeration();
}