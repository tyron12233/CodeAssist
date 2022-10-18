package org.gradle.internal.classloader;

import com.google.common.hash.HashCode;

/**
 * Mixed into a ClassLoader implementation to allow the implementation hash of a  ClassLoader to be queried
 */
public interface ImplementationHashAware {
    HashCode getImplementationHash();
}
