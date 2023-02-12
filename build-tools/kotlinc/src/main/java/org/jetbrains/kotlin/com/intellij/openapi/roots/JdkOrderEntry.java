package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.sdk.Sdk;

public interface JdkOrderEntry extends LibraryOrSdkOrderEntry {
  @Nullable
  Sdk getJdk();

  @Nullable
  String getJdkName();
}