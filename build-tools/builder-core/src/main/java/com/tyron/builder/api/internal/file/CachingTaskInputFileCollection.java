package com.tyron.builder.api.internal.file;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.api.internal.file.collections.DefaultConfigurableFileCollection;
import com.tyron.builder.api.internal.file.collections.FileCollectionAdapter;
import com.tyron.builder.api.internal.file.collections.ListBackedFileSet;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.api.internal.tasks.TaskDependencyFactory;
import com.tyron.builder.api.internal.tasks.properties.LifecycleAwareValue;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.internal.file.PathToFileResolver;

import java.io.File;
import java.util.function.Consumer;

/**
 * A {@link org.gradle.api.file.ConfigurableFileCollection} that can be used as a task input property. Caches the matching set of files during task execution, and discards the result after task execution.
 *
 * TODO - disallow further changes to this collection once task has started
 * TODO - keep the file entries to snapshot later, to avoid a stat on each file during snapshot
 */
public class CachingTaskInputFileCollection extends DefaultConfigurableFileCollection implements LifecycleAwareValue {
    private boolean canCache;
    private FileCollectionInternal cachedValue;

    // TODO - display name
    public CachingTaskInputFileCollection(PathToFileResolver fileResolver, Factory<PatternSet> patternSetFactory, TaskDependencyFactory taskDependencyFactory, PropertyHost propertyHost) {
        super(null, fileResolver, taskDependencyFactory, patternSetFactory, propertyHost);
    }

    @Override
    protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
        if (canCache) {
            if (cachedValue == null) {
                ImmutableSet.Builder<File> files = ImmutableSet.builder();
                super.visitChildren(files::addAll);
                this.cachedValue = new FileCollectionAdapter(new ListBackedFileSet(files.build()), patternSetFactory);
            }
            visitor.accept(cachedValue);
        } else {
            super.visitChildren(visitor);
        }
    }

    @Override
    public void prepareValue() {
        canCache = true;
    }

    @Override
    public void cleanupValue() {
        // Keep the files and discard the origin values instead?
        canCache = false;
        cachedValue = null;
    }
}
