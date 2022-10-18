package org.gradle.plugins.ide.internal.generator.generator;

import org.gradle.internal.Factory;

import java.io.File;

/**
 * Adapts a {@link PersistableConfigurationObject} to a {@link
 * Generator}.
 *
 * @param <T> the configuration object type.
 */
public abstract class PersistableConfigurationObjectGenerator<T extends PersistableConfigurationObject> implements Generator<T>, Factory<T> {
    @Override
    public T read(File inputFile) {
        T obj = create();
        obj.load(inputFile);
        return obj;
    }

    @Override
    public T defaultInstance() {
        T obj = create();
        obj.loadDefaults();
        return obj;
    }

    @Override
    public void write(T object, File outputFile) {
        object.store(outputFile);
    }
}