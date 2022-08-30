package com.tyron.code.gradle.util;

import android.content.SharedPreferences;

import com.tyron.code.ApplicationLoader;
import com.tyron.common.SharedPreferenceKeys;

import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.ConfigurableLauncher;

public class GradleLaunchUtil {

    /**
     * Configures the given launcher to align with the preferences set in the build settings
     */
    public static void configureLauncher(ConfigurableLauncher<?> launcher) {
        SharedPreferences preferences = ApplicationLoader.getDefaultPreferences();

        String logLevel = preferences.getString(SharedPreferenceKeys.GRADLE_LOG_LEVEL, "LIFECYCLE");
        switch (logLevel) {
            case "QUIET":
                launcher.addArguments("--quiet");
                break;
            case "WARN":
                launcher.addArguments("--warn");
                break;
            case "INFO":
                launcher.addArguments("--info");
                break;
            case "DEBUG":
                launcher.addArguments("--debug");
                break;
            default:
            case "LIFECYCLE":
                // intentionally empty
        }

        String stacktrace = preferences.getString(SharedPreferenceKeys.GRADLE_STACKTRACE_MODE, "INTERNAL_EXCEPTIONS");
        switch (stacktrace) {
            case "ALWAYS":
                launcher.addArguments("--stacktrace");
                break;
            case "ALWAYS_FULL":
                launcher.addArguments("--full-stacktrace");
                break;
            default:
            case "INTERNAL_EXCEPTIONS":
                // intentionally empty
        }
    }
}
