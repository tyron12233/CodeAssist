package org.gradle.tooling.provider.model.internal;

import org.gradle.api.Action;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

/**
 * Action on {@link ToolingModelBuilderRegistry} to register builders at build scope.
 *
 * @since 4.0
 */
public interface BuildScopeToolingModelBuilderRegistryAction extends Action<ToolingModelBuilderRegistry> {
}
