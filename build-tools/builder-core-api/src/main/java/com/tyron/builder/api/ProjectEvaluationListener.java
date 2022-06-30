package com.tyron.builder.api;

import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.api.invocation.Gradle;
import com.tyron.builder.api.BuildProject;

/**
 * <p>An {@code ProjectEvaluationListener} is notified when a project is evaluated. You add can add an {@code
 * ProjectEvaluationListener} to a {@link Gradle} using {@link
 * Gradle#addProjectEvaluationListener(ProjectEvaluationListener)}.</p>
 */
@EventScope(Scopes.Build.class)
public interface ProjectEvaluationListener {
    /**
     * This method is called immediately before a project is evaluated.
     *
     * @param project The which is to be evaluated. Never null.
     */
    void beforeEvaluate(BuildProject project);

    /**
     * <p>This method is called when a project has been evaluated, and before the evaluated project is made available to
     * other projects.</p>
     *
     * @param project The project which was evaluated. Never null.
     * @param state The project evaluation state. If project evaluation failed, the exception is available in this
     * state. Never null.
     */
    void afterEvaluate(BuildProject project, ProjectState state);
}