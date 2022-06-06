package com.tyron.builder.internal.jvm.inspection;

import com.tyron.builder.api.JavaVersion;
import com.tyron.builder.internal.jvm.JavaInfo;

/**
 * Probes a JVM installation to determine the Java version it provides.
 */
public interface JvmVersionDetector {
    /**
     * Probes the Java version for the given JVM installation.
     */
    JavaVersion getJavaVersion(JavaInfo jvm);

    /**
     * Probes the Java version for the given `java` command.
     */
    JavaVersion getJavaVersion(String javaCommand);
}
