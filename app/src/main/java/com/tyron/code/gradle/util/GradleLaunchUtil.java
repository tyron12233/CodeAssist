package com.tyron.code.gradle.util;

import static com.tyron.builder.model.AndroidProject.MODEL_LEVEL_3_VARIANT_OUTPUT_POST_BUILD;
import static com.tyron.builder.model.AndroidProject.PROPERTY_BUILD_MODEL_DISABLE_SRC_DOWNLOAD;
import static com.tyron.builder.model.AndroidProject.PROPERTY_BUILD_MODEL_ONLY;
import static com.tyron.builder.model.AndroidProject.PROPERTY_BUILD_MODEL_ONLY_ADVANCED;
import static com.tyron.builder.model.AndroidProject.PROPERTY_BUILD_MODEL_ONLY_VERSIONED;
import static com.tyron.builder.model.InjectedProperties.PROPERTY_INVOKED_FROM_IDE;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.tyron.code.ApplicationLoader;
import com.tyron.common.SharedPreferenceKeys;

import org.gradle.tooling.ConfigurableLauncher;

public class GradleLaunchUtil {


    private static void addIdeProperties(ConfigurableLauncher<?> launcher) {
        launcher.addArguments(createProjectProperty(PROPERTY_BUILD_MODEL_ONLY, true));
        launcher.addArguments(createProjectProperty(PROPERTY_BUILD_MODEL_ONLY_ADVANCED, true));
        launcher.addArguments(createProjectProperty(PROPERTY_INVOKED_FROM_IDE, true));
        // Sent to plugin starting with Studio 3.0
        launcher.addArguments(createProjectProperty(PROPERTY_BUILD_MODEL_ONLY_VERSIONED, MODEL_LEVEL_3_VARIANT_OUTPUT_POST_BUILD));

        // Skip download of source and javadoc jars during Gradle sync, this flag only has effect on AGP 3.5.
        //noinspection deprecation AGP 3.6 and above do not download sources at all.
        launcher.addArguments(createProjectProperty(PROPERTY_BUILD_MODEL_DISABLE_SRC_DOWNLOAD, true));
    }

    /**
     * Configures the given launcher to align with the preferences set in the build settings
     */
    public static void configureLauncher(ConfigurableLauncher<?> launcher) {
        addIdeProperties(launcher);

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

        String stacktrace = preferences.getString(SharedPreferenceKeys.GRADLE_STACKTRACE_MODE,
                "INTERNAL_EXCEPTIONS");
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

    private static final String PROJECT_PROPERTY_FORMAT = "-P%1$s=%2$s";

    @NonNull
    public static String createProjectProperty(@NonNull String name, boolean value) {
        return createProjectProperty(name, String.valueOf(value));
    }

    @NonNull
    public static String createProjectProperty(@NonNull String name, int value) {
        return createProjectProperty(name, String.valueOf(value));
    }

    @NonNull
    public static String createProjectProperty(@NonNull String name, @NonNull String value) {
        return String.format(PROJECT_PROPERTY_FORMAT, name, value);
    }
}
