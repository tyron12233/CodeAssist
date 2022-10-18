package org.gradle.api.launcher.cli;

import org.gradle.api.Incubating;

/**
 * The possible strategies for displaying a welcome message on the command line.
 *
 * @since 7.5
 */
@Incubating
public enum WelcomeMessageDisplayMode {
    ONCE, // the default, show the welcome message once per Gradle version
    NEVER // suppress the welcome message
}