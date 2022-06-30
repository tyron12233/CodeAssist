package com.tyron.builder.configuration.project;

import com.tyron.builder.internal.service.scopes.Scope;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import java.util.List;

/**
 * Provides metadata about a build-in command, which is a task-like action (usually backed by an actual task)
 * that can be invoked from the command-line, such as `gradle init ...` or `gradle help ...`
 *
 * A built-in command can be invoked from any directory, and does not require a Gradle build definition to be present.
 */
@ServiceScope(Scope.Global.class)
public interface BuiltInCommand {
    /**
     * Returns the list of task paths that should be used when none are specified by the user. Returns an empty list if this command should not be used as a default.
     */
    List<String> asDefaultTask();

    /**
     * Does the given list of task paths reference this command?
     */
    boolean commandLineMatches(List<String> taskNames);
}