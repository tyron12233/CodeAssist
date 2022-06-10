package org.gradle.api.internal.file.collections;

import org.gradle.api.tasks.util.PatternSet;

import java.io.File;

public interface DirectoryFileTreeFactory {
    DirectoryFileTree create(File directory);

    DirectoryFileTree create(File directory, PatternSet patternSet);
}