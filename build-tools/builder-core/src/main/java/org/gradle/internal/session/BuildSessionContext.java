package org.gradle.internal.session;

import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.buildtree.BuildActionRunner;

public interface BuildSessionContext {
    ServiceRegistry getServices();

    BuildActionRunner.Result execute(BuildAction action);
}