package org.jetbrains.kotlin.com.intellij.openapi.roots.libraries;

import org.jetbrains.kotlin.com.intellij.openapi.components.PersistentStateComponent;

/**
 * Represents additional properties of a library. Use {@link com.intellij.openapi.roots.libraries.DummyLibraryProperties} if libraries of
 * a custom type don't have any additional properties.
 */
public abstract class LibraryProperties<T> implements PersistentStateComponent<T> {
  @Override
  public abstract boolean equals(Object obj);

  @Override
  public abstract int hashCode();
}