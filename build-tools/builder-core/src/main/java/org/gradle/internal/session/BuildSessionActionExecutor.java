package org.gradle.internal.session;


import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.internal.buildtree.BuildActionRunner;

@ServiceScope(Scopes.BuildSession.class)
public interface BuildSessionActionExecutor {
    BuildActionRunner.Result execute(BuildAction action, BuildSessionContext context);
}