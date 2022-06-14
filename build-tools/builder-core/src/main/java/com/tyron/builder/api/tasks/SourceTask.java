package com.tyron.builder.api.tasks;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.file.ConfigurableFileCollection;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.internal.tasks.ConventionTask;
import com.tyron.builder.api.tasks.util.PatternFilterable;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.internal.Factory;

import java.util.Set;
import java.util.function.Predicate;

public class SourceTask extends ConventionTask implements PatternFilterable {

    private ConfigurableFileCollection sourceFiles = getProject().getObjects().fileCollection();
    private final PatternFilterable patternSet;

    public SourceTask() {
        patternSet = getPatternSetFactory().create();
    }

    @Internal
    protected Factory<PatternSet> getPatternSetFactory() {
        return getServices().getFactory(PatternSet.class);
    }

    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public FileTree getSource() {
        return sourceFiles.getAsFileTree().matching(patternSet);
    }

    /**
     * Sets the source for this task.
     *
     * @param source The source.
     * @since 4.0
     */
    public void setSource(FileTree source) {
        setSource((Object) source);
    }

    /**
     * Sets the source for this task. The given source object is evaluated as per
     * {@link BuildProject#files(Object...)}.
     *
     * @param source The source.
     */
    public void setSource(Object source) {
        sourceFiles = getProject().getObjects().fileCollection().from(source);
    }

    /**
     * Adds some source to this task. The given source objects will be evaluated as per
     * {@link BuildProject#files(Object...)}.
     *
     * @param sources The source to add
     * @return this
     */
    public SourceTask source(Object... sources) {
        sourceFiles.from(sources);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SourceTask include(String... includes) {
        patternSet.include(includes);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SourceTask include(Iterable<String> includes) {
        patternSet.include(includes);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SourceTask include(Predicate<FileTreeElement> includeSpec) {
        patternSet.include(includeSpec);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SourceTask exclude(String... excludes) {
        patternSet.exclude(excludes);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SourceTask exclude(Iterable<String> excludes) {
        patternSet.exclude(excludes);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SourceTask exclude(Predicate<FileTreeElement> excludeSpec) {
        patternSet.exclude(excludeSpec);
        return this;
    }


    /**
     * {@inheritDoc}
     */
    @Internal
    @Override
    public Set<String> getIncludes() {
        return patternSet.getIncludes();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SourceTask setIncludes(Iterable<String> includes) {
        patternSet.setIncludes(includes);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Internal
    @Override
    public Set<String> getExcludes() {
        return patternSet.getExcludes();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SourceTask setExcludes(Iterable<String> excludes) {
        patternSet.setExcludes(excludes);
        return this;
    }
}
