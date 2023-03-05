package com.tyron.builder.merge;

import com.android.annotations.NonNull;
import java.io.IOException;
import java.util.List;

/**
 * {@link IncrementalFileMergerOutput} that delegates execution to another
 * {@link IncrementalFileMergerOutput}. Invoking methods on an instance will delegate execution to
 * the delegate.
 */
public class DelegateIncrementalFileMergerOutput implements IncrementalFileMergerOutput {

    /**
     * Delegate that will receive the calls.
     */
    @NonNull
    private final IncrementalFileMergerOutput delegate;

    /**
     * Creates a new output.
     *
     * @param delegate the delegate
     */
    public DelegateIncrementalFileMergerOutput(@NonNull IncrementalFileMergerOutput delegate) {
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

    @Override
    public void remove(@NonNull String path) {
        delegate.remove(path);
    }

    @Override
    public void create(
            @NonNull String path,
            @NonNull List<IncrementalFileMergerInput> inputs,
            boolean compress) {
        delegate.create(path, inputs, compress);
    }

    @Override
    public void update(
            @NonNull String path,
            @NonNull List<String> prevInputNames,
            @NonNull List<IncrementalFileMergerInput> inputs,
            boolean compress) {
        delegate.update(path, prevInputNames, inputs, compress);
    }
}
