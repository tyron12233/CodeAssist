package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.file.DuplicatesStrategy;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.reflect.Instantiator;

public class SingleParentCopySpec extends DefaultCopySpec {

    private final CopySpecResolver parentResolver;

    public SingleParentCopySpec(FileCollectionFactory fileCollectionFactory, Instantiator instantiator, Factory<PatternSet> patternSetFactory, CopySpecResolver parentResolver) {
        super(fileCollectionFactory, instantiator, patternSetFactory);
        this.parentResolver = parentResolver;
    }

    @Override
    public CopySpecInternal addChild() {
        DefaultCopySpec child = new SingleParentCopySpec(fileCollectionFactory, instantiator, patternSetFactory, buildResolverRelativeToParent(parentResolver));
        addChildSpec(child);
        return child;
    }

    @Override
    protected CopySpecInternal addChildAtPosition(int position) {
        DefaultCopySpec child = instantiator.newInstance(SingleParentCopySpec.class, fileCollectionFactory, instantiator, patternSetFactory, buildResolverRelativeToParent(parentResolver));
        addChildSpec(position, child);
        return child;
    }

    @Override
    public boolean isCaseSensitive() {
        return buildResolverRelativeToParent(parentResolver).isCaseSensitive();
    }

    @Override
    public boolean getIncludeEmptyDirs() {
        return buildResolverRelativeToParent(parentResolver).getIncludeEmptyDirs();
    }

    @Override
    public DuplicatesStrategy getDuplicatesStrategy() {
        return buildResolverRelativeToParent(parentResolver).getDuplicatesStrategy();
    }

    @Override
    public Integer getDirMode() {
        return buildResolverRelativeToParent(parentResolver).getDirMode();
    }

    @Override
    public Integer getFileMode() {
        return buildResolverRelativeToParent(parentResolver).getFileMode();
    }

    @Override
    public String getFilteringCharset() {
        return buildResolverRelativeToParent(parentResolver).getFilteringCharset();
    }
}
