package com.tyron.builder.initialization.exception;

import com.tyron.builder.execution.MultipleBuildFailures;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An exception analyser that deals specifically with MultipleBuildFailures and transforms each component failure.
 */
public class MultipleBuildFailuresExceptionAnalyser implements ExceptionAnalyser {
    private final ExceptionCollector collector;

    public MultipleBuildFailuresExceptionAnalyser(ExceptionCollector collector) {
        this.collector = collector;
    }

    @Override
    public RuntimeException transform(Throwable failure) {
        return transform(Collections.singletonList(failure));
    }

    @Nullable
    @Override
    public RuntimeException transform(List<Throwable> failures) {
        if (failures.isEmpty()) {
            return null;
        }

        List<Throwable> result = new ArrayList<>(failures.size());
        for (Throwable failure : failures) {
            if (failure instanceof MultipleBuildFailures) {
                for (Throwable cause : ((MultipleBuildFailures) failure).getCauses()) {
                    collector.collectFailures(cause, result);
                }
            } else {
                collector.collectFailures(failure, result);
            }
        }
        if (result.size() == 1 && result.get(0) instanceof RuntimeException) {
            return (RuntimeException) result.get(0);
        } else {
            return new MultipleBuildFailures(result);
        }
    }
}