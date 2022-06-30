package com.tyron.builder.api.internal.file.collections;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.file.CompositeFileCollection;
import com.tyron.builder.api.internal.file.FileCollectionInternal;

import java.util.function.Consumer;

/**
 * A {@link FileCollection} whose contents is created lazily.
 */
public abstract class LazilyInitializedFileCollection extends CompositeFileCollection {
    private FileCollectionInternal delegate;

    @Override
    protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
        if (delegate == null) {
            delegate = (FileCollectionInternal) createDelegate();
        }
        visitor.accept(delegate);
    }

    public abstract FileCollection createDelegate();
}