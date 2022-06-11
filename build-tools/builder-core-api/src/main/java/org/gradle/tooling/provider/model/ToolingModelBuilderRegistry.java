package org.gradle.tooling.provider.model;

import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

/**
 * A registry of tooling model builders. Adding a builder to this registry makes a model (or models) available via the tooling API.
 *
 * <p>An instance of this type can be injected into a task, plugin or other object by annotating a public constructor or property getter method with {@code javax.inject.Inject}.
 */
@ServiceScope(Scopes.Build.class)
public interface ToolingModelBuilderRegistry {
    void register(ToolingModelBuilder builder);

    ToolingModelBuilder getBuilder(String modelName) throws UnknownModelException;
}
