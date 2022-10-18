package org.gradle.launcher.configuration;

import java.util.Map;

/**
 * An immutable view of all properties available for build options, calculated from command-line options, the environment and the various
 * properties files.
 */
public interface AllProperties {
    /**
     * Returns the system properties defined as command-line options.
     */
    Map<String, String> getRequestedSystemProperties();

    /**
     * Returns all properties that should be considered to calculate build option values.
     */
    Map<String, String> getProperties();

    AllProperties merge(Map<String, String> systemProperties);
}