package com.tyron.builder.api.internal.artifacts.dependencies;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.internal.typeconversion.NotationParser;

import java.util.List;
import java.util.Set;

public class DefaultMutableModuleDependencyCapabilitiesHandler implements ModuleDependencyCapabilitiesInternal {
    private final NotationParser<Object, Capability> notationParser;
    private final Set<Capability> requestedCapabilities = Sets.newLinkedHashSet();

    public DefaultMutableModuleDependencyCapabilitiesHandler(NotationParser<Object, Capability> notationParser) {
        this.notationParser = notationParser;
    }

    @Override
    public void requireCapability(Object capabilityNotation) {
        requestedCapabilities.add(notationParser.parseNotation(capabilityNotation));
    }

    @Override
    public void requireCapabilities(Object... capabilityNotations) {
        for (Object notation : capabilityNotations) {
            requireCapability(notation);
        }
    }

    @Override
    public List<Capability> getRequestedCapabilities() {
        return ImmutableList.copyOf(requestedCapabilities);
    }

    @Override
    public ModuleDependencyCapabilitiesInternal copy() {
        DefaultMutableModuleDependencyCapabilitiesHandler out = new DefaultMutableModuleDependencyCapabilitiesHandler(notationParser);
        out.requestedCapabilities.addAll(requestedCapabilities);
        return out;
    }
}
