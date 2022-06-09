package com.tyron.builder.internal.featurelifecycle;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable description of the usage of a deprecated feature.
 */
public abstract class FeatureUsage {

    private final String summary;
    private final Class<?> calledFrom;
    private final Exception traceException;

    private List<StackTraceElement> stack;

    protected FeatureUsage(String summary, Class<?> calledFrom) {
        this(summary, calledFrom, new Exception());
    }

    @VisibleForTesting
    protected FeatureUsage(String summary, Class<?> calledFrom, Exception traceException) {
        this.summary = summary;
        this.calledFrom = calledFrom;
        this.traceException = traceException;
    }

    /**
     * A concise sentence summarising the usage.
     *
     * Example: Method Foo.bar() has been deprecated.
     */
    public String getSummary() {
        return summary;
    }

    protected Class<?> getCalledFrom() {
        return calledFrom;
    }

    public List<StackTraceElement> getStack() {
        if (stack == null) {
            stack = calculateStack(calledFrom, traceException);
        }
        return stack;
    }

    private static List<StackTraceElement> calculateStack(Class<?> calledFrom, Exception traceRoot) {
        StackTraceElement[] originalStack = traceRoot.getStackTrace();
        List<StackTraceElement> result = new ArrayList<StackTraceElement>();
        final String calledFromName = calledFrom.getName();
        boolean calledFromFound = false;
        int caller;
        for (caller = 0; caller < originalStack.length; caller++) {
            StackTraceElement current = originalStack[caller];
            if (!calledFromFound) {
                if (current.getClassName().startsWith(calledFromName)) {
                    calledFromFound = true;
                }
            } else {
                if (!current.getClassName().startsWith(calledFromName)) {
                    break;
                }
            }
        }
        for (; caller < originalStack.length; caller++) {
            StackTraceElement stackTraceElement = originalStack[caller];
            if (!isSystemStackFrame(stackTraceElement.getClassName())) {
                result.add(stackTraceElement);
            }
        }
        return result;
    }

    private static boolean isSystemStackFrame(String className) {
        return className.startsWith("jdk.internal.") ||
            className.startsWith("sun.") ||
            className.startsWith("com.sun.") ||
            className.startsWith("org.codehaus.groovy.") ||
            className.startsWith("com.tyron.builder.internal.metaobject.") ||
            className.startsWith("com.tyron.builder.kotlin.dsl.execution.");
    }

    public String formattedMessage() {
        return summary;
    }

}
