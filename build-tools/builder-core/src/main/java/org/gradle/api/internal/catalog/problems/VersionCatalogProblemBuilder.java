package org.gradle.api.internal.catalog.problems;

import org.gradle.problems.StandardSeverity;

import java.util.function.Supplier;

public interface VersionCatalogProblemBuilder {
    ProblemWithId inContext(Supplier<String> context);

    default ProblemWithId inContext(String context) {
        return inContext(() -> context);
    }

    interface ProblemWithId {
        ProblemWithId withSeverity(StandardSeverity severity);

        DescribedProblem withShortDescription(Supplier<String> description);

        default DescribedProblem withShortDescription(String description) {
            return withShortDescription(() -> description);
        }
    }

    interface DescribedProblem {
        DescribedProblem withLongDescription(Supplier<String> description);
        default DescribedProblem withLongDescription(String description) {
            return withLongDescription(() -> description);
        }

        DescribedProblemWithCause happensBecause(Supplier<String> reason);
        default DescribedProblemWithCause happensBecause(String reason) {
            return happensBecause(() -> reason);
        }
    }

    interface DescribedProblemWithCause {
        DescribedProblemWithCause documented();
        DescribedProblemWithCause documentedAt(String page, String section);
        DescribedProblemWithCause addSolution(Supplier<String> solution);
        default DescribedProblemWithCause addSolution(String solution) {
            return addSolution(() -> solution);
        }
    }
}
