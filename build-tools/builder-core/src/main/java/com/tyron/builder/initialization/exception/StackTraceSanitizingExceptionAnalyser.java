package com.tyron.builder.initialization.exception;

import com.google.common.base.Throwables;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class StackTraceSanitizingExceptionAnalyser implements ExceptionAnalyser {
    private final ExceptionAnalyser analyser;

    public StackTraceSanitizingExceptionAnalyser(ExceptionAnalyser analyser) {
        this.analyser = analyser;
    }

    @Override
    public RuntimeException transform(Throwable failure) {
        return (RuntimeException) deepSanitize(analyser.transform(failure));
    }

    @Nullable
    @Override
    public RuntimeException transform(List<Throwable> failures) {
        RuntimeException result = analyser.transform(failures);
        if (result == null) {
            return null;
        }
        return (RuntimeException) deepSanitize(result);
    }

    /**
     * Sanitize the exception and ALL nested causes
     * <p>
     * This will MODIFY the stacktrace of the exception instance and all its causes irreversibly
     *
     * @param t a throwable
     * @return The root cause exception instances, with stack trace modified to filter out groovy runtime classes
     */
    public static Throwable deepSanitize(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = sanitize(current.getCause());
        }
        return sanitize(t);
    }

    /**
     * Remove all apparently groovy-internal trace entries from the exception instance
     * <p>
     * This modifies the original instance and returns it, it does not clone
     *
     * @param t the Throwable whose stack trace we want to sanitize
     * @return The original Throwable but with a sanitized stack trace
     */
    public static Throwable sanitize(Throwable t) {
        // Note that this getBoolean access may well be synced...
        if (!Boolean.getBoolean("groovy.full.stacktrace")) {
            StackTraceElement[] trace = t.getStackTrace();
            List<StackTraceElement> newTrace = new ArrayList<StackTraceElement>();
            for (StackTraceElement stackTraceElement : trace) {
                if (isApplicationClass(stackTraceElement.getClassName())) {
                    newTrace.add(stackTraceElement);
                }
            }

            // We don't want to lose anything, so log it
//            STACK_LOG.log(Level.WARNING, "Sanitizing stacktrace:", t);

            StackTraceElement[] clean = new StackTraceElement[newTrace.size()];
            newTrace.toArray(clean);
            t.setStackTrace(clean);
        }
        return t;
    }

    public static boolean isApplicationClass(String className) {
        return true;
    }
}