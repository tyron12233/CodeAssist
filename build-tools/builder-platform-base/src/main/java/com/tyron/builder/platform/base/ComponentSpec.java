package com.tyron.builder.platform.base;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.internal.HasInternalProtocol;
import com.tyron.builder.model.ModelElement;

/**
 * A software component that is built by Gradle.
 */
@Incubating
@HasInternalProtocol
public interface ComponentSpec extends ModelElement {
    /**
     * The path to the project containing this component.
     */
    String getProjectPath();
}
