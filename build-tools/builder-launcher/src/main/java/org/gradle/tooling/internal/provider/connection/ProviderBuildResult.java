package org.gradle.tooling.internal.provider.connection;

import org.gradle.tooling.internal.protocol.BuildResult;

public class ProviderBuildResult<T> implements BuildResult<T> {
    private final T result;

    public ProviderBuildResult(T result) {
        this.result = result;
    }

    @Override
    public T getModel() {
        return result;
    }
}
