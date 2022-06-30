package com.tyron.builder.api.internal.file.collections;

/**
 * A minimal file collection. An implementation can optionally also implement the following interfaces:
 *
 * <ul>
 * <li>{@link org.gradle.api.Buildable}</li>
 * <li>{@link RandomAccessFileCollection}</li>
 * </ul>
 */
public interface MinimalFileCollection {
    String getDisplayName();
}