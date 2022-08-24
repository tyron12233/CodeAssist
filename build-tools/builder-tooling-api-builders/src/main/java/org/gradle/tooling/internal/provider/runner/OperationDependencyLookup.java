package org.gradle.tooling.internal.provider.runner;

import org.gradle.execution.plan.Node;
import org.gradle.tooling.internal.protocol.events.InternalOperationDescriptor;

interface OperationDependencyLookup {

    InternalOperationDescriptor lookupExistingOperationDescriptor(Node node);

}