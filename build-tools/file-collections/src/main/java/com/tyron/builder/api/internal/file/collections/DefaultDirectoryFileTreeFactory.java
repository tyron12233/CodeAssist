package com.tyron.builder.api.internal.file.collections;

import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.nativeintegration.services.FileSystems;

import java.io.File;

public class DefaultDirectoryFileTreeFactory implements DirectoryFileTreeFactory {
    private final Factory<PatternSet> patternSetFactory;
    private final FileSystem fileSystem;

    public DefaultDirectoryFileTreeFactory() {
        this.patternSetFactory = PatternSet::new;
        this.fileSystem = FileSystems.getDefault();
    }

    public DefaultDirectoryFileTreeFactory(Factory<PatternSet> patternSetFactory, FileSystem fileSystem) {
        this.patternSetFactory = patternSetFactory;
        this.fileSystem = fileSystem;
    }

    @Override
    public DirectoryFileTree create(File directory) {
        return new DirectoryFileTree(directory, patternSetFactory.create(), fileSystem);
    }

    @Override
    public DirectoryFileTree create(File directory, PatternSet patternSet) {
        return new DirectoryFileTree(directory, patternSet, fileSystem);
    }
}
