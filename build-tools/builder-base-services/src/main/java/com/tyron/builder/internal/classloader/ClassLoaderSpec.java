package com.tyron.builder.internal.classloader;

import java.io.Serializable;

/**
 * An immutable description of a ClassLoader hierarchy that can be used to recreate the hierarchy in a different process.
 *
 * Subclasses should implement equals() and hashCode(), so that the spec can be used as a hashmap key.
 */
public abstract class ClassLoaderSpec implements Serializable {
}