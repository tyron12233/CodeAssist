package com.tyron.builder.internal.operations;

import com.tyron.builder.internal.exceptions.DefaultMultiCauseException;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;

public class MultipleBuildOperationFailures extends DefaultMultiCauseException {
    private static final int MAX_CAUSES = 10;

    public MultipleBuildOperationFailures(Collection<? extends Throwable> causes, @Nullable String logLocation) {
        super(format(getFailureMessage(causes), causes, logLocation), causes);
    }

    private static String getFailureMessage(Collection<? extends Throwable> failures) {
        if (failures.size() == 1) {
            return "A build operation failed.";
        }
        return "Multiple build operations failed.";
    }

    private static String format(String message, Iterable<? extends Throwable> causes, @Nullable String logLocation) {
        StringBuilder sb = new StringBuilder(message);
        int count = 0;
        for (Throwable cause : causes) {
            if (count++ < MAX_CAUSES) {
                sb.append(String.format("%n    %s", cause.getMessage()));
            }
        }

        int suppressedFailureCount = count - MAX_CAUSES;
        if (suppressedFailureCount == 1) {
            sb.append(String.format("%n    ...and %d more failure.", suppressedFailureCount));
        } else if (suppressedFailureCount > 1) {
            sb.append(String.format("%n    ...and %d more failures.", suppressedFailureCount));
        }

        if (logLocation != null) {
            sb.append(String.format("%nSee the complete log at: ")).append(logLocation);
        }
        return sb.toString();
    }
}