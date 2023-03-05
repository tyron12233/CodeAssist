package org.gradle.api.internal.file.collections;

import java.io.File;
import java.util.Set;

/**
 * A minimal file set.
 */
public interface MinimalFileSet extends MinimalFileCollection {
    Set<File> getFiles();
}