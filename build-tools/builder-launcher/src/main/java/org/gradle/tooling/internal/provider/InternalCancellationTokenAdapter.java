package org.gradle.tooling.internal.provider;

import org.gradle.initialization.BuildCancellationToken;
import org.gradle.tooling.internal.protocol.InternalCancellationToken;

public class InternalCancellationTokenAdapter implements BuildCancellationToken, InternalCancellationToken {
    private final InternalCancellationToken cancellationToken;

    public InternalCancellationTokenAdapter(InternalCancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken;
    }

    @Override
    public boolean isCancellationRequested() {
        return cancellationToken.isCancellationRequested();
    }

    @Override
    public boolean addCallback(Runnable cancellationHandler) {
        return cancellationToken.addCallback(cancellationHandler);
    }

    @Override
    public void removeCallback(Runnable cancellationHandler) {
        cancellationToken.removeCallback(cancellationHandler);
    }

    @Override
    public void cancel() {
        throw new UnsupportedOperationException();
    }
}
