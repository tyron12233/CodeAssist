package org.gradle.api.internal.file.collections;

import org.gradle.api.tasks.util.PatternFilterable;

/**
 * A file tree which can provide an efficient implementation for filtering using patterns.
 */
public interface PatternFilterableFileTree extends MinimalFileTree {
    MinimalFileTree filter(PatternFilterable patterns);
}