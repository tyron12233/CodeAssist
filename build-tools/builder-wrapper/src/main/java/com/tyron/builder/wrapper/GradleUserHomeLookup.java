package com.tyron.builder.wrapper;

import java.io.File;

public class GradleUserHomeLookup {
    public static final String DEFAULT_GRADLE_USER_HOME = System.getProperty("user.home") + "/.gradle";
    public static final String GRADLE_USER_HOME_PROPERTY_KEY = "gradle.user.home";
    public static final String GRADLE_USER_HOME_ENV_KEY = "GRADLE_USER_HOME";

    public static File gradleUserHome() {
        String gradleUserHome;
        if ((gradleUserHome = System.getProperty(GRADLE_USER_HOME_PROPERTY_KEY)) != null) {
            return new File(gradleUserHome);
        }
        if ((gradleUserHome = System.getenv(GRADLE_USER_HOME_ENV_KEY)) != null) {
            return new File(gradleUserHome);
        }
        return new File(DEFAULT_GRADLE_USER_HOME);
    }
}
