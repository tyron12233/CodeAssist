package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.file.ConfigurableFileCollection;
import com.tyron.builder.api.file.Directory;
import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.ProjectLayout;
import com.tyron.builder.api.file.RegularFile;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.api.internal.file.collections.MinimalFileSet;
import com.tyron.builder.api.internal.provider.MappingProvider;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.api.internal.provider.Providers;
import com.tyron.builder.api.internal.tasks.TaskDependencyFactory;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;

public class DefaultProjectLayout implements ProjectLayout, TaskFileVarFactory {
    private final Directory projectDir;
    private final DirectoryProperty buildDir;
    private final FileResolver fileResolver;
    private final TaskDependencyFactory taskDependencyFactory;
    private final Factory<PatternSet> patternSetFactory;
    private final PropertyHost propertyHost;
    private final FileCollectionFactory fileCollectionFactory;
    private final FileFactory fileFactory;

    public DefaultProjectLayout(File projectDir, FileResolver fileResolver, TaskDependencyFactory taskDependencyFactory, Factory<PatternSet> patternSetFactory, PropertyHost propertyHost, FileCollectionFactory fileCollectionFactory, FilePropertyFactory filePropertyFactory, FileFactory fileFactory) {
        this.fileResolver = fileResolver;
        this.taskDependencyFactory = taskDependencyFactory;
        this.patternSetFactory = patternSetFactory;
        this.propertyHost = propertyHost;
        this.fileCollectionFactory = fileCollectionFactory;
        this.fileFactory = fileFactory;
        this.projectDir = fileFactory.dir(projectDir);
        this.buildDir = filePropertyFactory.newDirectoryProperty().convention(fileFactory.dir(fileResolver.resolve(
                BuildProject.DEFAULT_BUILD_DIR_NAME)));
    }

    @Override
    public Directory getProjectDirectory() {
        return projectDir;
    }

    @Override
    public DirectoryProperty getBuildDirectory() {
        return buildDir;
    }

    @Override
    public ConfigurableFileCollection newInputFileCollection(Task consumer) {
        return new CachingTaskInputFileCollection(fileResolver, patternSetFactory, taskDependencyFactory, propertyHost);
    }

    @Override
    public FileCollection newCalculatedInputFileCollection(Task consumer, MinimalFileSet calculatedFiles, FileCollection... inputs) {
        return new CalculatedTaskInputFileCollection(consumer.getPath(), calculatedFiles, inputs);
    }

    @Override
    public Provider<RegularFile> file(Provider<File> provider) {
        return new MappingProvider<>(RegularFile.class, Providers.internal(provider), new Transformer<RegularFile, File>() {
            @Override
            public RegularFile transform(File file) {
                return fileFactory.file(fileResolver.resolve(file));
            }
        });
    }

    @Override
    public Provider<Directory> dir(Provider<File> provider) {
        return new MappingProvider<>(Directory.class, Providers.internal(provider), new Transformer<Directory, File>() {
            @Override
            public Directory transform(File file) {
                return fileFactory.dir(fileResolver.resolve(file));
            }
        });
    }

    @Override
    public FileCollection files(Object... paths) {
        return fileCollectionFactory.resolving(paths);
    }

    /**
     * A temporary home. Should be on the public API somewhere
     */
    public void setBuildDirectory(Object value) {
        buildDir.set(fileResolver.resolve(value));
    }
}

