package org.gradle.tooling.internal.provider.runner;

import org.gradle.execution.plan.Node;
import org.gradle.tooling.internal.protocol.events.InternalOperationDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.util.stream.Collectors.toCollection;

class OperationDependenciesResolver {

    private final List<OperationDependencyLookup> lookups = new ArrayList<>();

    void addLookup(OperationDependencyLookup lookup) {
        lookups.add(lookup);
    }

    Set<InternalOperationDescriptor> resolveDependencies(Node node) {
        return node.getDependencySuccessors().stream()
            .map(this::lookupExistingOperationDescriptor)
            .filter(Objects::nonNull)
            .collect(toCollection(LinkedHashSet::new));
    }

    private InternalOperationDescriptor lookupExistingOperationDescriptor(Node node) {
        return lookups.stream()
            .map(entry -> entry.lookupExistingOperationDescriptor(node))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

}