package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;

import javax.annotation.Nullable;

/**
 * <p>A {@code Module} represents the meta-information about a project which should be used when publishing the
 * module.</p>
 */
public interface Module {
    String DEFAULT_STATUS = "integration";

    @Nullable
    ProjectComponentIdentifier getProjectId();

    String getGroup();

    String getName();

    String getVersion();

    String getStatus();
}