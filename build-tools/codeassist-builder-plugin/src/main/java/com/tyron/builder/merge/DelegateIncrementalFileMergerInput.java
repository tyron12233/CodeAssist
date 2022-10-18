package com.tyron.builder.merge;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ide.common.resources.FileStatus;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.InputStream;

/**
 * {@link IncrementalFileMergerInput} that delegates all operations to another
 * {@link IncrementalFileMergerInput}. This can be used as a base class for extending inputs.
 * This class will delegate all methods to the delegate input.
 */
public class DelegateIncrementalFileMergerInput implements IncrementalFileMergerInput {

    /**
     * The instance to delegate to.
     */
    @NonNull
    private final IncrementalFileMergerInput delegate;

    /**
     * Creates a new input.
     *
     * @param delegate the delegate calls to
     */
    public DelegateIncrementalFileMergerInput(@NonNull IncrementalFileMergerInput delegate) {
        this.delegate = delegate;
    }

    @Override
    public void open() {
        delegate.open();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @NonNull
    @Override
    public ImmutableSet<String> getUpdatedPaths() {
        return delegate.getUpdatedPaths();
    }

    @NonNull
    @Override
    public ImmutableSet<String> getAllPaths() {
        return delegate.getAllPaths();
    }

    @NonNull
    @Override
    public String getName() {
        return delegate.getName();
    }

    @Nullable
    @Override
    public FileStatus getFileStatus(@NonNull String path) {
        return delegate.getFileStatus(path);
    }

    @NonNull
    @Override
    public InputStream openPath(@NonNull String path) {
        return delegate.openPath(path);
    }
}
