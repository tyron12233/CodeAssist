package com.tyron.builder.api.internal.classloading;

/**
 * Doesn't need to do anything.
 */
public class NoOpGroovySystemLoader implements GroovySystemLoader {
    @Override
    public void shutdown() {
    }

    @Override
    public void discardTypesFrom(ClassLoader classLoader) {
    }
}
