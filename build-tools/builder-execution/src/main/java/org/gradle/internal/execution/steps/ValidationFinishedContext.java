package org.gradle.internal.execution.steps;

import com.google.common.collect.ImmutableCollection;
import org.gradle.internal.reflect.validation.TypeValidationProblem;

import java.util.Optional;

public interface ValidationFinishedContext extends BeforeExecutionContext {
    /**
     * Returns validation warnings or {@link Optional#empty()} if there were no validation problems.
     */
    Optional<ValidationResult> getValidationProblems();

    interface ValidationResult {
        ImmutableCollection<TypeValidationProblem> getWarnings();
    }
}