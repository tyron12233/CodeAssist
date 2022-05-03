package com.tyron.builder.execution;

import com.google.common.collect.ImmutableSortedSet;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.steps.ValidateStep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class DefaultWorkValidationWarningRecorder implements ValidateStep.ValidationWarningRecorder, WorkValidationWarningReporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultWorkValidationWarningRecorder.class);

    private final AtomicInteger workWithFailuresCount = new AtomicInteger();

    @Override
    public void recordValidationWarnings(UnitOfWork work, Collection<String> warnings) {
        workWithFailuresCount.incrementAndGet();
        Set<String> uniqueSortedWarnings = ImmutableSortedSet.copyOf(warnings);
        LOGGER.warn("Execution optimizations have been disabled for {} to ensure correctness due to the following reasons:{}",
            work.getDisplayName(),
            uniqueSortedWarnings.stream()
                .map(warning -> "\n  - " + warning)
                .collect(Collectors.joining()));
//        uniqueSortedWarnings.forEach(warning -> DeprecationLogger.deprecateBehaviour(warning)
//            .withContext("Execution optimizations are disabled to ensure correctness.")
//            .willBeRemovedInGradle8()
//            .withUserManual("more_about_tasks", "sec:up_to_date_checks")
//            .nagUser());
    }

    @Override
    public void reportWorkValidationWarningsAtEndOfBuild() {
        int workWithFailures = workWithFailuresCount.getAndSet(0);
        if (workWithFailures > 0) {
            LOGGER.warn(
                "\nExecution optimizations have been disabled for {} invalid unit(s) of work during this build to ensure correctness." +
                "\nPlease consult deprecation warnings for more details.",
                workWithFailures
            );
        }
    }
}
