package org.gradle.api.internal.artifacts.dependencies;

import org.gradle.api.artifacts.ModuleDependencyCapabilitiesHandler;
import org.gradle.api.capabilities.Capability;

import java.util.List;

public interface ModuleDependencyCapabilitiesInternal extends ModuleDependencyCapabilitiesHandler {
    List<Capability> getRequestedCapabilities();

    ModuleDependencyCapabilitiesInternal copy();
}
