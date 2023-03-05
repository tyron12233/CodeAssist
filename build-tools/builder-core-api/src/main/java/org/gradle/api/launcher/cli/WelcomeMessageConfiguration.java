package org.gradle.api.launcher.cli;

import org.gradle.api.Incubating;

import java.io.Serializable;

/**
 * Configures when to display the welcome message on the command line.
 *
 * @since 7.5
 */
@Incubating
public class WelcomeMessageConfiguration implements Serializable {

    private WelcomeMessageDisplayMode welcomeMessageDisplayMode;

    public WelcomeMessageConfiguration(WelcomeMessageDisplayMode welcomeMessageDisplayMode) {
        this.welcomeMessageDisplayMode = welcomeMessageDisplayMode;
    }

    public WelcomeMessageDisplayMode getWelcomeMessageDisplayMode() {
        return welcomeMessageDisplayMode;
    }

    public void setWelcomeMessageDisplayMode(WelcomeMessageDisplayMode welcomeMessageDisplayMode) {
        this.welcomeMessageDisplayMode = welcomeMessageDisplayMode;
    }
}