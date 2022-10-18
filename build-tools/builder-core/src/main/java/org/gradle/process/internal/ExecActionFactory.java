package org.gradle.process.internal;

import org.gradle.internal.service.scopes.Scope;
import org.gradle.internal.service.scopes.ServiceScope;

@ServiceScope(Scope.Global.class)
public interface ExecActionFactory {
    /**
     * Creates an {@link ExecAction} that is not decorated. Use this when the action is not made visible to the DSL.
     * If you need to make the action visible to the DSL, use {@link org.gradle.process.ExecOperations} instead.
     */
    ExecAction newExecAction();

    /**
     * Creates a {@link JavaExecAction} that is not decorated. Use this when the action is not made visible to the DSL.
     * If you need to make the action visible to the DSL, use {@link org.gradle.process.ExecOperations} instead.
     */
    JavaExecAction newJavaExecAction();
}
