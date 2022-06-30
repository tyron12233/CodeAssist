package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Incubating;

import java.util.List;

/**
 * A bundle is a list of dependencies which are always added together.
 *
 * @since 6.8
 */
@Incubating
public interface ExternalModuleDependencyBundle extends List<MinimalExternalModuleDependency> {
}
