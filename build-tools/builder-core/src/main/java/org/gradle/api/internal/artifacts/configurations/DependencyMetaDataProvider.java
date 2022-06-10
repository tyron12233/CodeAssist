package org.gradle.api.internal.artifacts.configurations;

import org.gradle.api.internal.artifacts.Module;

public interface DependencyMetaDataProvider {
    Module getModule();
}
