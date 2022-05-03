package com.tyron.builder.internal.session;


import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.internal.buildtree.BuildActionRunner;

@ServiceScope(Scopes.BuildSession.class)
public interface BuildSessionActionExecutor {
    BuildActionRunner.Result execute(BuildAction action, BuildSessionContext context);
}