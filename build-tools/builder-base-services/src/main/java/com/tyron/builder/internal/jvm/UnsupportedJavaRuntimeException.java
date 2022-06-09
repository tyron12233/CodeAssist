package com.tyron.builder.internal.jvm;

import com.tyron.builder.api.JavaVersion;
import com.tyron.builder.util.GradleVersion;

public class UnsupportedJavaRuntimeException extends RuntimeException {

    public UnsupportedJavaRuntimeException(String message) {
        super(message);
    }

    public static void assertUsingVersion(String component, JavaVersion minVersion) throws UnsupportedJavaRuntimeException {
        JavaVersion current = JavaVersion.current();
        if (current.compareTo(minVersion) >= 0) {
            return;
        }
        throw new UnsupportedJavaRuntimeException(String.format("%s %s requires Java %s or later to run. You are currently using Java %s.", component, GradleVersion.current().getVersion(),
            minVersion.getMajorVersion(), current.getMajorVersion()));
    }

    public static void assertUsingVersion(String component, JavaVersion minVersion, JavaVersion configuredVersion) throws UnsupportedJavaRuntimeException {
        if (configuredVersion.compareTo(minVersion) >= 0) {
            return;
        }
        throw new UnsupportedJavaRuntimeException(String.format("%s %s requires Java %s or later to run. Your build is currently configured to use Java %s.", component, GradleVersion.current().getVersion(),
            minVersion.getMajorVersion(), configuredVersion.getMajorVersion()));
    }
}
