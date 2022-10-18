package org.gradle.plugins.ide.internal.generator.generator;

import java.io.File;

/**
 * Responsible for reading, configuring and writing a config object of type T to/from a file.
 * @param <T>
 */
public interface Generator<T> {
    T read(File inputFile);

    T defaultInstance();

    void configure(T object);

    void write(T object, File outputFile);
}