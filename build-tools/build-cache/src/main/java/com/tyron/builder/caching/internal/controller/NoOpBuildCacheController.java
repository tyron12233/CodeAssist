package com.tyron.builder.caching.internal.controller;

import java.util.Optional;

public class NoOpBuildCacheController implements BuildCacheController {

    public static final BuildCacheController INSTANCE = new NoOpBuildCacheController();

    private NoOpBuildCacheController() {
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public boolean isEmitDebugLogging() {
        return false;
    }

    @Override
    public <T> Optional<T> load(BuildCacheLoadCommand<T> command) {
        return Optional.empty();
    }

    @Override
    public void store(BuildCacheStoreCommand command) {

    }

    @Override
    public void close() {

    }

}
