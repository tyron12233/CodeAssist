package com.tyron.builder.internal.session;

import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.buildtree.BuildActionRunner;

public interface BuildSessionContext {
    ServiceRegistry getServices();

    BuildActionRunner.Result execute(BuildAction action);
}