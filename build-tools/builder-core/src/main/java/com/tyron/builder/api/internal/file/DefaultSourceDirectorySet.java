package com.tyron.builder.api.internal.file;

import groovy.lang.Closure;
import com.tyron.builder.api.Buildable;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.file.Directory;
import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.file.DirectoryTree;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.file.SourceDirectorySet;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTree;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.internal.file.collections.FileCollectionAdapter;
import com.tyron.builder.api.internal.file.collections.MinimalFileSet;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.specs.Spec;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.api.tasks.TaskProvider;
import com.tyron.builder.api.tasks.util.PatternFilterable;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.deprecation.DeprecationLogger;
import com.tyron.builder.util.internal.GUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class DefaultSourceDirectorySet extends CompositeFileTree implements SourceDirectorySet {
    private final List<Object> source = new ArrayList<>();
    private final String name;
    private final String displayName;
    private final FileCollectionFactory fileCollectionFactory;
    private final DirectoryFileTreeFactory directoryFileTreeFactory;
    private final PatternSet patterns;
    private final PatternSet filter;
    private final FileCollection dirs;
    private final DirectoryProperty destinationDirectory; // the user configurable output directory
    private final DirectoryProperty classesDirectory;     // bound to the compile task output

    private TaskProvider<?> compileTaskProvider;

    public DefaultSourceDirectorySet(String name, String displayName, Factory<PatternSet> patternSetFactory, FileCollectionFactory fileCollectionFactory, DirectoryFileTreeFactory directoryFileTreeFactory, ObjectFactory objectFactory) {
        this(name, displayName, patternSetFactory.create(), patternSetFactory.create(), fileCollectionFactory, directoryFileTreeFactory, objectFactory.directoryProperty(), objectFactory.directoryProperty());
    }

    DefaultSourceDirectorySet(String name, String displayName, PatternSet patterns, PatternSet filters, FileCollectionFactory fileCollectionFactory, DirectoryFileTreeFactory directoryFileTreeFactory, DirectoryProperty destinationDirectory, DirectoryProperty classesDirectory) {
        this.name = name;
        this.displayName = displayName;
        this.fileCollectionFactory = fileCollectionFactory;
        this.directoryFileTreeFactory = directoryFileTreeFactory;
        this.patterns = patterns;
        this.filter = filters;
        this.dirs = new FileCollectionAdapter(new SourceDirectories());
        this.destinationDirectory = destinationDirectory;
        this.classesDirectory = classesDirectory;
    }

    public DefaultSourceDirectorySet(SourceDirectorySet sourceSet) {
        if (!(sourceSet instanceof DefaultSourceDirectorySet)) {
            throw new RuntimeException("Invalid source set type:" + source.getClass());
        }
        DefaultSourceDirectorySet defaultSourceSet = (DefaultSourceDirectorySet) sourceSet;
        this.name = defaultSourceSet.name;
        this.displayName = defaultSourceSet.displayName;
        this.fileCollectionFactory = defaultSourceSet.fileCollectionFactory;
        this.directoryFileTreeFactory = defaultSourceSet.directoryFileTreeFactory;
        this.patterns = defaultSourceSet.patterns;
        this.filter = defaultSourceSet.filter;
        this.dirs = new FileCollectionAdapter(new SourceDirectories());
        this.destinationDirectory = defaultSourceSet.destinationDirectory;
        this.classesDirectory = defaultSourceSet.classesDirectory;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public FileCollection getSourceDirectories() {
        return dirs;
    }

    @Override
    public Set<File> getSrcDirs() {
        Set<File> dirs = new LinkedHashSet<>();
        for (DirectoryTree tree : getSrcDirTrees()) {
            dirs.add(tree.getDir());
        }
        return dirs;
    }

    @Override
    public Set<String> getIncludes() {
        return patterns.getIncludes();
    }

    @Override
    public Set<String> getExcludes() {
        return patterns.getExcludes();
    }

    @Override
    public PatternFilterable setIncludes(Iterable<String> includes) {
        patterns.setIncludes(includes);
        return this;
    }

    @Override
    public PatternFilterable setExcludes(Iterable<String> excludes) {
        patterns.setExcludes(excludes);
        return this;
    }

    @Override
    public PatternFilterable include(String... includes) {
        patterns.include(includes);
        return this;
    }

    @Override
    public PatternFilterable include(Iterable<String> includes) {
        patterns.include(includes);
        return this;
    }

    @Override
    public PatternFilterable include(Predicate<FileTreeElement> includeSpec) {
        patterns.include(includeSpec);
        return this;
    }
//
//    @Override
//    public PatternFilterable include(Closure includeSpec) {
//        patterns.include(includeSpec);
//        return this;
//    }

    @Override
    public PatternFilterable exclude(Iterable<String> excludes) {
        patterns.exclude(excludes);
        return this;
    }

    @Override
    public PatternFilterable exclude(String... excludes) {
        patterns.exclude(excludes);
        return this;
    }

    @Override
    public PatternFilterable exclude(Predicate<FileTreeElement> excludeSpec) {
        patterns.exclude(excludeSpec);
        return this;
    }

//    @Override
//    public PatternFilterable exclude(Closure excludeSpec) {
//        patterns.exclude(excludeSpec);
//        return this;
//    }

    @Override
    public PatternFilterable getFilter() {
        return filter;
    }

    @Override
    @Deprecated
    public File getOutputDir() {
        DeprecationLogger.deprecateProperty(SourceDirectorySet.class, "outputDir")
            .replaceWith("classesDirectory")
            .willBeRemovedInGradle8()
            .withDslReference()
            .nagUser();

        return destinationDirectory.getAsFile().get();
    }

    @Override
    @Deprecated
    public void setOutputDir(Provider<File> provider) {
        DeprecationLogger.deprecateMethod(SourceDirectorySet.class, "setOutputDir(Provider<File>)")
            .withAdvice("Please use the destinationDirectory property instead.")
            .willBeRemovedInGradle8()
            .withDslReference(SourceDirectorySet.class, "destinationDirectory")
            .nagUser();

        destinationDirectory.set(classesDirectory.fileProvider(provider));
    }

    @Override
    @Deprecated
    public void setOutputDir(File outputDir) {
        DeprecationLogger.deprecateMethod(SourceDirectorySet.class, "setOutputDir(File)")
            .withAdvice("Please use the destinationDirectory property instead.")
            .willBeRemovedInGradle8()
            .withDslReference(SourceDirectorySet.class, "destinationDirectory")
            .nagUser();

        destinationDirectory.set(outputDir);
    }

    @Override
    public DirectoryProperty getDestinationDirectory() {
        return destinationDirectory;
    }

    @Override
    public Provider<Directory> getClassesDirectory() {
        return classesDirectory;
    }

    @Override
    public <T extends Task> void compiledBy(TaskProvider<T> taskProvider, Function<T, DirectoryProperty> mapping) {
        this.compileTaskProvider = taskProvider;
        taskProvider.configure(task -> {
            if (taskProvider == this.compileTaskProvider) {
                mapping.apply(task).set(destinationDirectory);
            }
        });
        classesDirectory.set(taskProvider.flatMap(mapping::apply));
    }

    @Override
    public Set<DirectoryTree> getSrcDirTrees() {
        // This implementation is broken. It does not consider include and exclude patterns
        Map<File, DirectoryTree> trees = new LinkedHashMap<>();
        for (DirectoryTree tree : getSourceTrees()) {
            if (!trees.containsKey(tree.getDir())) {
                trees.put(tree.getDir(), tree);
            }
        }
        return new LinkedHashSet<>(trees.values());
    }

    protected Set<DirectoryFileTree> getSourceTrees() {
        Set<DirectoryFileTree> result = new LinkedHashSet<>();
        for (Object path : source) {
            if (path instanceof DefaultSourceDirectorySet) {
                DefaultSourceDirectorySet nested = (DefaultSourceDirectorySet) path;
                result.addAll(nested.getSourceTrees());
            } else {
                for (File srcDir : fileCollectionFactory.resolving(path)) {
                    if (srcDir.exists() && !srcDir.isDirectory()) {
                        throw new InvalidUserDataException(String.format("Source directory '%s' is not a directory.", srcDir));
                    }
                    result.add(directoryFileTreeFactory.create(srcDir, patterns));
                }
            }
        }
        return result;
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        for (Object path : source) {
            if (path instanceof SourceDirectorySet) {
                context.add(((SourceDirectorySet) path).getBuildDependencies());
            } else {
                context.add(fileCollectionFactory.resolving(path));
            }
        }
    }

    @Override
    protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
        for (DirectoryFileTree directoryTree : getSourceTrees()) {
            visitor.accept(fileCollectionFactory.treeOf(directoryTree.filter(filter)));
        }
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public SourceDirectorySet srcDir(Object srcDir) {
        source.add(srcDir);
        return this;
    }

    @Override
    public SourceDirectorySet srcDirs(Object... srcDirs) {
        source.addAll(Arrays.asList(srcDirs));
        return this;
    }

    @Override
    public SourceDirectorySet setSrcDirs(Iterable<?> srcPaths) {
        source.clear();
        GUtil.addToCollection(source, srcPaths);
        return this;
    }

    @Override
    public SourceDirectorySet source(SourceDirectorySet source) {
        this.source.add(source);
        return this;
    }

    private class SourceDirectories implements MinimalFileSet, Buildable {
        @Override
        public TaskDependency getBuildDependencies() {
            return DefaultSourceDirectorySet.this.getBuildDependencies();
        }

        @Override
        public Set<File> getFiles() {
            return getSrcDirs();
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }
    }
}
