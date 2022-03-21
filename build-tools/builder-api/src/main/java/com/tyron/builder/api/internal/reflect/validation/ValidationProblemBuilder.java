package com.tyron.builder.api.internal.reflect.validation;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.reflect.problems.ValidationProblemId;

import java.util.function.Supplier;

@SuppressWarnings("UnusedReturnValue")
public interface ValidationProblemBuilder<T extends ValidationProblemBuilder<T>> {
    T withId(ValidationProblemId id);

    T withDescription(Supplier<String> message);

    T happensBecause(Supplier<String> message);

    default T happensBecause(String message) {
        return happensBecause(() -> message);
    }

    default T withDescription(String message) {
        return withDescription(() -> message);
    }

    T reportAs(Severity severity);

    T withLongDescription(Supplier<String> longDescription);

    default T withLongDescription(String longDescription) {
        return withLongDescription(() -> longDescription);
    }

    T documentedAt(String id, String section);

    T addPossibleSolution(Supplier<String> solution, Action<? super SolutionBuilder> solutionSpec);

    default T addPossibleSolution(Supplier<String> solution) {
        return addPossibleSolution(solution, solutionBuilder -> {

        });
    }

    default T addPossibleSolution(String solution) {
        return addPossibleSolution(() -> solution);
    }

    T onlyAffectsCacheableWork();


    /**
     * Indicates that whenever this error is reported to the user,
     * it's not important, or even sometimes confusing, to report the type
     * on which it happened. This is the case for ad-hoc types (DefaultTask)
     * or, for example, when a problem happens because of ordering issues
     * and that it can be reported on multiple types.
     */
    T typeIsIrrelevantInErrorMessage();
}