package org.gradle.launcher.configuration;

import java.util.Map;

/**
 * An immutable view of the properties that are available prior to calculating the build layout. That is, the properties that are
 * defined on the command-line and in the system properties of the current JVM.
 */
public interface InitialProperties {
    /**
     * Returns the system properties defined as command-line options.
     */
    Map<String, String> getRequestedSystemProperties();
}