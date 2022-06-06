package com.tyron.builder.api.internal.classloading;

import com.tyron.builder.util.internal.CollectionUtils;

import java.util.List;

public class CompositeGroovySystemLoader implements GroovySystemLoader {
    private final List<GroovySystemLoader> loaders;

    public CompositeGroovySystemLoader(GroovySystemLoader... loaders) {
        this.loaders = CollectionUtils.toList(loaders);
    }

    @Override
    public void shutdown() {
        for (GroovySystemLoader loader : loaders) {
            loader.shutdown();
        }
    }

    @Override
    public void discardTypesFrom(ClassLoader classLoader) {
        for (GroovySystemLoader loader : loaders) {
            loader.discardTypesFrom(classLoader);
        }
    }
}
