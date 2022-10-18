package org.gradle.internal.build.event.types;

import org.gradle.tooling.internal.protocol.InternalFailure;
import org.gradle.tooling.internal.protocol.InternalTestFrameworkFailure;

import java.util.Collections;
import java.util.List;

public class DefaultTestFrameworkFailure extends AbstractTestFailure implements InternalTestFrameworkFailure {

    public DefaultTestFrameworkFailure(String message, String description, List<? extends InternalFailure> cause, String className, String stacktrace) {
        super(message, description, cause, className, stacktrace);
    }

    public static DefaultTestFrameworkFailure create(Throwable t, String message, String className, String stacktrace) {
        Throwable cause = t.getCause();
        List<InternalFailure> causeFailure = cause != null && cause != t ? Collections.singletonList(DefaultFailure.fromThrowable(cause)) : Collections.emptyList();
        return new DefaultTestFrameworkFailure(message, stacktrace, causeFailure, className, stacktrace);
    }
}