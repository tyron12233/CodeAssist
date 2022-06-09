package com.tyron.builder.api.tasks;

import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.NonNullApi;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.file.CopySpec;
import com.tyron.builder.api.file.DuplicatesStrategy;
import com.tyron.builder.api.file.ExpandDetails;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileCopyDetails;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.internal.ConventionTask;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.file.FileLookup;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.internal.file.copy.ClosureBackedTransformer;
import com.tyron.builder.api.internal.file.copy.CopyAction;
import com.tyron.builder.api.internal.file.copy.CopyActionExecuter;
import com.tyron.builder.api.internal.file.copy.CopySpecInternal;
import com.tyron.builder.api.internal.file.copy.CopySpecResolver;
import com.tyron.builder.api.internal.file.copy.CopySpecSource;
import com.tyron.builder.api.internal.file.copy.DefaultCopySpec;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.util.internal.ClosureBackedAction;
import com.tyron.builder.work.DisableCachingByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.FilterReader;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.tyron.builder.api.internal.lambdas.SerializableLambdas.spec;

/**
 * {@code AbstractCopyTask} is the base class for all copy tasks.
 */
@NonNullApi
@DisableCachingByDefault(because = "Abstract super-class, not to be instantiated directly")
public abstract class AbstractCopyTask extends ConventionTask implements CopySpec, CopySpecSource {

    private final CopySpecInternal rootSpec;
    private final CopySpecInternal mainSpec;

    protected AbstractCopyTask() {
        this.rootSpec = createRootSpec();
        rootSpec.addChildSpecListener((path, spec) -> {
            if (getState().getExecuting()) {
                throw new BuildException("You cannot add child specs at execution time. Consider configuring this task during configuration time or using a separate task to do the configuration.");
            }

            StringBuilder specPropertyNameBuilder = new StringBuilder("rootSpec");
            CopySpecResolver parentResolver = path.unroll(specPropertyNameBuilder);
            CopySpecResolver resolver = spec.buildResolverRelativeToParent(parentResolver);
            String specPropertyName = specPropertyNameBuilder.toString();

            getInputs().files((Callable<FileTree>) resolver::getSource)
                .withPropertyName(specPropertyName)
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .ignoreEmptyDirectories(false)
                .skipWhenEmpty();

            getInputs().property(specPropertyName + ".destPath", (Callable<String>) () -> resolver.getDestPath().getPathString());
            getInputs().property(specPropertyName + ".caseSensitive", (Callable<Boolean>) spec::isCaseSensitive);
            getInputs().property(specPropertyName + ".includeEmptyDirs", (Callable<Boolean>) spec::getIncludeEmptyDirs);
            getInputs().property(specPropertyName + ".duplicatesStrategy", (Callable<DuplicatesStrategy>) spec::getDuplicatesStrategy);
            getInputs().property(specPropertyName + ".dirMode", (Callable<Integer>) spec::getDirMode)
                .optional(true);
            getInputs().property(specPropertyName + ".fileMode", (Callable<Integer>) spec::getFileMode)
                .optional(true);
            getInputs().property(specPropertyName + ".filteringCharset", (Callable<String>) spec::getFilteringCharset);
        });
        this.getOutputs().doNotCacheIf(
            "Has custom actions",
            spec(task -> rootSpec.hasCustomActions())
        );
        this.mainSpec = rootSpec.addChild();
    }

    protected CopySpecInternal createRootSpec() {
        return getProject().getObjects().newInstance(DefaultCopySpec.class);
    }

    protected abstract CopyAction createCopyAction();

    @Inject
    protected Instantiator getInstantiator() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected FileSystem getFileSystem() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected FileResolver getFileResolver() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected FileLookup getFileLookup() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected DirectoryFileTreeFactory getDirectoryFileTreeFactory() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected DocumentationRegistry getDocumentationRegistry() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected ObjectFactory getObjectFactory() {
        throw new UnsupportedOperationException();
    }

    @TaskAction
    protected void copy() {
        CopyActionExecuter copyActionExecuter = createCopyActionExecuter();
        CopyAction copyAction = createCopyAction();
        WorkResult didWork = copyActionExecuter.execute(rootSpec, copyAction);
        setDidWork(didWork.getDidWork());
    }

    protected CopyActionExecuter createCopyActionExecuter() {
        Instantiator instantiator = getInstantiator();
        FileSystem fileSystem = getFileSystem();

        return new CopyActionExecuter(instantiator, getObjectFactory(), fileSystem, false, getDocumentationRegistry());
    }

    /**
     * Returns the source files for this task.
     *
     * @return The source files. Never returns null.
     */
    @Internal
    public FileCollection getSource() {
        return rootSpec.buildRootResolver().getAllSource();
    }

    @Internal
    @Override
    public CopySpecInternal getRootSpec() {
        return rootSpec;
    }

    // -----------------------------------------------
    // ---- Delegate CopySpec methods to rootSpec ----
    // -----------------------------------------------

    @Internal
    protected CopySpecInternal getMainSpec() {
        return mainSpec;
    }

    /**
     * {@inheritDoc}
     */
    @Internal
    @Override
    public boolean isCaseSensitive() {
        return getMainSpec().isCaseSensitive();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCaseSensitive(boolean caseSensitive) {
        getMainSpec().setCaseSensitive(caseSensitive);
    }

    /**
     * {@inheritDoc}
     */
    @Internal
    @Override
    public boolean getIncludeEmptyDirs() {
        return getMainSpec().getIncludeEmptyDirs();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setIncludeEmptyDirs(boolean includeEmptyDirs) {
        getMainSpec().setIncludeEmptyDirs(includeEmptyDirs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDuplicatesStrategy(DuplicatesStrategy strategy) {
        getRootSpec().setDuplicatesStrategy(strategy);
    }

    /**
     * {@inheritDoc}
     */
    @Internal
    @Override
    public DuplicatesStrategy getDuplicatesStrategy() {
        return getRootSpec().getDuplicatesStrategy();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask from(Object... sourcePaths) {
        getMainSpec().from(sourcePaths);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask filesMatching(String pattern, Action<? super FileCopyDetails> action) {
        getMainSpec().filesMatching(pattern, action);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask filesMatching(Iterable<String> patterns, Action<? super FileCopyDetails> action) {
        getMainSpec().filesMatching(patterns, action);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask filesNotMatching(String pattern, Action<? super FileCopyDetails> action) {
        getMainSpec().filesNotMatching(pattern, action);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask filesNotMatching(Iterable<String> patterns, Action<? super FileCopyDetails> action) {
        getMainSpec().filesNotMatching(patterns, action);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask from(Object sourcePath, Closure c) {
        getMainSpec().from(sourcePath, new ClosureBackedAction<>(c));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask from(Object sourcePath, Action<? super CopySpec> configureAction) {
        getMainSpec().from(sourcePath, configureAction);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CopySpec with(CopySpec... sourceSpecs) {
        getMainSpec().with(sourceSpecs);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask into(Object destDir) {
        getRootSpec().into(destDir);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask into(Object destPath, Closure configureClosure) {
        getMainSpec().into(destPath, configureClosure);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CopySpec into(Object destPath, Action<? super CopySpec> copySpec) {
        getMainSpec().into(destPath, copySpec);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask include(String... includes) {
        getMainSpec().include(includes);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask include(Iterable<String> includes) {
        getMainSpec().include(includes);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask include(Predicate<FileTreeElement> includeSpec) {
        getMainSpec().include(includeSpec);
        return this;
    }
//
//    /**
//     * {@inheritDoc}
//     */
//    @Override
//    public AbstractCopyTask include(Closure includeSpec) {
//        getMainSpec().include(includeSpec);
//        return this;
//    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask exclude(String... excludes) {
        getMainSpec().exclude(excludes);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask exclude(Iterable<String> excludes) {
        getMainSpec().exclude(excludes);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask exclude(Predicate<FileTreeElement> excludeSpec) {
        getMainSpec().exclude(excludeSpec);
        return this;
    }

//    /**
//     * {@inheritDoc}
//     */
//    @Override
//    public AbstractCopyTask exclude(Closure excludeSpec) {
//        getMainSpec().exclude(excludeSpec);
//        return this;
//    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask setIncludes(Iterable<String> includes) {
        getMainSpec().setIncludes(includes);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Internal
    @Override
    public Set<String> getIncludes() {
        return getMainSpec().getIncludes();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask setExcludes(Iterable<String> excludes) {
        getMainSpec().setExcludes(excludes);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Internal
    @Override
    public Set<String> getExcludes() {
        return getMainSpec().getExcludes();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask rename(Closure closure) {
        return rename(new ClosureBackedTransformer(closure));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask rename(Transformer<String, String> renamer) {
        getMainSpec().rename(renamer);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask rename(String sourceRegEx, String replaceWith) {
        getMainSpec().rename(sourceRegEx, replaceWith);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask rename(Pattern sourceRegEx, String replaceWith) {
        getMainSpec().rename(sourceRegEx, replaceWith);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask filter(Map<String, ?> properties, Class<? extends FilterReader> filterType) {
        getMainSpec().filter(properties, filterType);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask filter(Class<? extends FilterReader> filterType) {
        getMainSpec().filter(filterType);
        return this;
    }

//    /**
//     * {@inheritDoc}
//     */
//    @Override
//    public AbstractCopyTask filter(Closure closure) {
//        getMainSpec().filter(closure);
//        return this;
//    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask filter(Transformer<String, String> transformer) {
        getMainSpec().filter(transformer);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask expand(Map<String, ?> properties) {
        getMainSpec().expand(properties);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask expand(Map<String, ?> properties, Action<? super ExpandDetails> action) {
        getMainSpec().expand(properties, action);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Internal
    @Override
    public Integer getDirMode() {
        return getMainSpec().getDirMode();
    }

    /**
     * {@inheritDoc}
     */
    @Internal
    @Override
    public Integer getFileMode() {
        return getMainSpec().getFileMode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask setDirMode(@Nullable Integer mode) {
        getMainSpec().setDirMode(mode);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask setFileMode(@Nullable Integer mode) {
        getMainSpec().setFileMode(mode);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask eachFile(Action<? super FileCopyDetails> action) {
        getMainSpec().eachFile(action);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCopyTask eachFile(Closure closure) {
        getMainSpec().eachFile(closure);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Internal
    @Override
    public String getFilteringCharset() {
        return getMainSpec().getFilteringCharset();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setFilteringCharset(String charset) {
        getMainSpec().setFilteringCharset(charset);
    }
}
