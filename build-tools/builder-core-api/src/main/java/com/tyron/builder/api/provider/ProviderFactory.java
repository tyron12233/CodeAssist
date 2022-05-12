package com.tyron.builder.api.provider;

import java.util.concurrent.Callable;

public class ProviderFactory {
    public <T> Provider<T> provider(Callable<T> value) {
        return null;
    }
}
