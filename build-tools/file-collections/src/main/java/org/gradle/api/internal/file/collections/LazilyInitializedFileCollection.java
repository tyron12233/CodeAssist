package org.gradle.api.internal.file.collections;

import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.file.CompositeFileCollection;
import org.gradle.api.internal.file.FileCollectionInternal;

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