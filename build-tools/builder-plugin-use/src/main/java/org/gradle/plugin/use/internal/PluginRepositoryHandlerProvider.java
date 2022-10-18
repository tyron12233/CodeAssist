package org.gradle.plugin.use.internal;

import org.gradle.api.artifacts.dsl.RepositoryHandler;

public interface PluginRepositoryHandlerProvider {

    RepositoryHandler getPluginRepositoryHandler();
}
