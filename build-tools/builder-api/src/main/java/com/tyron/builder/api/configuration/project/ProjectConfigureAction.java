package com.tyron.builder.api.configuration.project;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.project.ProjectInternal;

/**
 * Can be implemented by plugins to auto-configure each project.
 *
 * <p>Implementations are discovered using the JAR service locator mechanism (see {@link org.gradle.internal.service.ServiceLocator}).
 * Each action is invoked for each project that is to be configured, before the project has been configured. Actions are executed
 * in an arbitrary order.
 */
public interface ProjectConfigureAction extends Action<ProjectInternal> {
}
