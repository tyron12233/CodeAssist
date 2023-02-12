package org.jetbrains.kotlin.com.intellij.openapi.roots;

public interface OrderEnumeratorSettings {
  boolean isProductionOnly();
  boolean isCompileOnly();
  boolean isRuntimeOnly();
}