package com.tyron.builder.api.internal.file.collections;

import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;

public interface DirectoryFileTreeFactory {
    DirectoryFileTree create(File directory);

    DirectoryFileTree create(File directory, PatternSet patternSet);
}