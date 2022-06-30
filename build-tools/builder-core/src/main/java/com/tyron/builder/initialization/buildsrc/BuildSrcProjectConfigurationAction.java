package com.tyron.builder.initialization.buildsrc;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.project.ProjectInternal;

/**
 * Can be implemented by plugins to auto-configure the buildSrc root project.
 *
 * <p>Implementations are discovered using the JAR service locator mechanism (see {@link com.tyron.builder.internal.service.ServiceLocator}).
 * Each action is invoked for the buildSrc project that is to be configured, before the project has been configured. Actions are executed
 * in an arbitrary order.
 */
public interface BuildSrcProjectConfigurationAction extends Action<ProjectInternal> {
}
