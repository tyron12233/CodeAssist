package org.gradle.internal.jvm.inspection;

import org.gradle.api.JavaVersion;
import org.gradle.internal.jvm.JavaInfo;

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
