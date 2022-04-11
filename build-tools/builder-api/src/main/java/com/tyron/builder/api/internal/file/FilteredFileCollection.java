package com.tyron.builder.api.internal.file;

import com.google.common.collect.Iterators;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.util.CollectionUtils;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class FilteredFileCollection extends AbstractFileCollection {
    private final FileCollectionInternal collection;
    private final Predicate<? super File> filterSpec;

    public FilteredFileCollection(AbstractFileCollection collection, Predicate<? super File> filterSpec) {
        super(collection.patternSetFactory);
        this.collection = collection;
        this.filterSpec = filterSpec;
    }

    @Override
    public FileCollectionInternal replace(FileCollectionInternal original, Supplier<FileCollectionInternal> supplier) {
        AbstractFileCollection newCollection = (AbstractFileCollection) collection.replace(original, supplier);
        if (newCollection == collection) {
            return this;
        }
        return new FilteredFileCollection(newCollection, filterSpec);
    }

    public FileCollectionInternal getCollection() {
        return collection;
    }

    public Predicate<? super File> getFilterSpec() {
        return filterSpec;
    }

    @Override
    public String getDisplayName() {
        return "file collection";
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        collection.visitDependencies(context);
    }

    @Override
    public Set<File> getFiles() {
        return CollectionUtils.filter(collection, new LinkedHashSet<>(), filterSpec);
    }

    @Override
    public boolean contains(File file) {
        return collection.contains(file) && filterSpec.test(file);
    }

    @Override
    public Iterator<File> iterator() {
        return Iterators.filter(collection.iterator(), filterSpec::test);
    }
}