package com.tyron.builder.api.internal.file.collections;

import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.api.internal.file.CompositeFileTree;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.internal.file.PathToFileResolver;
import com.tyron.builder.api.internal.tasks.TaskDependencyFactory;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependency;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class DefaultConfigurableFileTree extends CompositeFileTree implements ConfigurableFileTree {
    private Object dir;
    private final PatternSet patternSet;
    private final PathToFileResolver resolver;
    private final DefaultTaskDependency buildDependency;
    private final DirectoryFileTreeFactory directoryFileTreeFactory;

    public DefaultConfigurableFileTree(PathToFileResolver resolver, Factory<PatternSet> patternSetFactory, TaskDependencyFactory taskDependencyFactory, DirectoryFileTreeFactory directoryFileTreeFactory) {
        this.resolver = resolver;
        this.directoryFileTreeFactory = directoryFileTreeFactory;
        patternSet = patternSetFactory.create();
        buildDependency = taskDependencyFactory.configurableDependency();
    }

    @Override
    public PatternSet getPatterns() {
        return patternSet;
    }

    @Override
    public DefaultConfigurableFileTree setDir(Object dir) {
        from(dir);
        return this;
    }

    @Override
    public File getDir() {
        if (dir == null) {
            throw new InvalidUserDataException("A base directory must be specified in the task or via a method argument!");
        }
        return resolver.resolve(dir);
    }

    @Override
    public DefaultConfigurableFileTree from(Object dir) {
        this.dir = dir;
        return this;
    }

    @Override
    public String getDisplayName() {
        return "directory '" + dir + "'";
    }

    @Override
    public Set<String> getIncludes() {
        return patternSet.getIncludes();
    }

    @Override
    public DefaultConfigurableFileTree setIncludes(Iterable<String> includes) {
        patternSet.setIncludes(includes);
        return this;
    }

    @Override
    public Set<String> getExcludes() {
        return patternSet.getExcludes();
    }

    @Override
    public DefaultConfigurableFileTree setExcludes(Iterable<String> excludes) {
        patternSet.setExcludes(excludes);
        return this;
    }

    @Override
    public DefaultConfigurableFileTree include(String... includes) {
        patternSet.include(includes);
        return this;
    }

    @Override
    public DefaultConfigurableFileTree include(Iterable<String> includes) {
        patternSet.include(includes);
        return this;
    }

//    @Override
//    public DefaultConfigurableFileTree include(Closure includeSpec) {
//        patternSet.include(includeSpec);
//        return this;
//    }

    @Override
    public DefaultConfigurableFileTree include(Predicate<FileTreeElement> includeSpec) {
        patternSet.include(includeSpec);
        return this;
    }

    @Override
    public DefaultConfigurableFileTree exclude(String... excludes) {
        patternSet.exclude(excludes);
        return this;
    }

    @Override
    public DefaultConfigurableFileTree exclude(Iterable<String> excludes) {
        patternSet.exclude(excludes);
        return this;
    }

    @Override
    public DefaultConfigurableFileTree exclude(Predicate<FileTreeElement> excludeSpec) {
        patternSet.exclude(excludeSpec);
        return this;
    }

//    @Override
//    public DefaultConfigurableFileTree exclude(Closure excludeSpec) {
//        patternSet.exclude(excludeSpec);
//        return this;
//    }

    @Override
    protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
        File dir = getDir();
        visitor.accept(new FileTreeAdapter(directoryFileTreeFactory.create(dir, patternSet), patternSetFactory));
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        context.add(buildDependency);
    }

    @Override
    public ConfigurableFileTree builtBy(Object... tasks) {
        buildDependency.add(tasks);
        return this;
    }

    @Override
    public Set<Object> getBuiltBy() {
        return buildDependency.getMutableValues();
    }

    @Override
    public ConfigurableFileTree setBuiltBy(Iterable<?> tasks) {
        buildDependency.setValues(tasks);
        return this;
    }
}