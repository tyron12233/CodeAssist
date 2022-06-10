package org.gradle.api;

import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.api.invocation.Gradle;

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
    void beforeEvaluate(Project project);

    /**
     * <p>This method is called when a project has been evaluated, and before the evaluated project is made available to
     * other projects.</p>
     *
     * @param project The project which was evaluated. Never null.
     * @param state The project evaluation state. If project evaluation failed, the exception is available in this
     * state. Never null.
     */
    void afterEvaluate(Project project, ProjectState state);
}