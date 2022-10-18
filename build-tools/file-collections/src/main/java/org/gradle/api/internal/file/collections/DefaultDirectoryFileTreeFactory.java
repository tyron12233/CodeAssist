package org.gradle.api.internal.file.collections;

import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.Factory;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.internal.nativeintegration.services.FileSystems;

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
