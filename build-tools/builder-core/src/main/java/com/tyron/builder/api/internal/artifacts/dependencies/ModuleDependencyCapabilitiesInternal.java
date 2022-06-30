package com.tyron.builder.api.internal.artifacts.dependencies;

import com.tyron.builder.api.artifacts.ModuleDependencyCapabilitiesHandler;
import com.tyron.builder.api.capabilities.Capability;

import java.util.List;

public interface ModuleDependencyCapabilitiesInternal extends ModuleDependencyCapabilitiesHandler {
    List<Capability> getRequestedCapabilities();

    ModuleDependencyCapabilitiesInternal copy();
}
