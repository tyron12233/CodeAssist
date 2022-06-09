package com.tyron.builder.process;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

/**
 * Process execution operations.
 *
 * <p>An instance of this type can be injected into a task, plugin or other object by annotating a public constructor or property getter method with {@code javax.inject.Inject}.
 *
 * @since 6.0
 */
@ServiceScope(Scopes.Build.class)
public interface ExecOperations {

    /**
     * Executes the specified external process.
     * The given action is used to configure an {@link ExecSpec}, which is then used to run an external process.
     *
     * @param action Action to configure the ExecSpec
     * @return {@link ExecResult} that can be used to check if the execution worked
     */
    ExecResult exec(Action<? super ExecSpec> action);

    /**
     * Executes the specified external <code>java</code> process.
     * The given action is used to configure an {@link JavaExecSpec}, which is then used to run an external <code>java</code> process.
     *
     * @param action Action to configure the JavaExecSpec
     * @return {@link ExecResult} that can be used to check if the execution worked
     */
    ExecResult javaexec(Action<? super JavaExecSpec> action);
}
