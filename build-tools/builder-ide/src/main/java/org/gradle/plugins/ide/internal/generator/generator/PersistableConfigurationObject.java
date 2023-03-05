package org.gradle.plugins.ide.internal.generator.generator;

import java.io.File;

public interface PersistableConfigurationObject {
    void load(File inputFile);

    void loadDefaults();

    void store(File outputFile);
}