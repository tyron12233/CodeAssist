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

import org.apache.commons.io.FileUtils;
import org.gradle.tooling.ConfigurableLauncher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

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

    public static void addCodeAssistInitScript(ConfigurableLauncher<?> launcher) {
        try {
            launcher.addArguments("--init-script", getOrCreateInitScript().getAbsolutePath());
        } catch (IOException e) {
            // this should not happen under normal circumstances.
            // throw just in case
            throw new RuntimeException(e);
        }
    }

    private static File getOrCreateInitScript() throws IOException {
        File initScript = new File(ApplicationLoader.getInstance().getFilesDir(), "init_scripts/init_script.gradle");
        //noinspection ResultOfMethodCallIgnored
        Objects.requireNonNull(initScript.getParentFile()).mkdirs();
        if (!initScript.exists() && !initScript.createNewFile()) {
            throw new IOException();
        }

        //language=Groovy
        String initScriptCode = "rootProject.buildscript.configurations.classpath {\n" +
                                "    resolutionStrategy.eachDependency {\n" +
                                "        if (it.requested.name == \"gradle\" && it.requested" +
                                ".group == \"com.android.tools.build\") {\n" +
                                "            throw new GradleException(\"The Android Gradle " +
                                "Plugin has been applied but is not supported. CodeAssist " +
                                "maintains its own\" +\n" +
                                "                    \"version of the Android Gradle Plugin so " +
                                "you don't have to include it in the build script's\" +\n" +
                                "                    \"classpath.\")\n" +
                                "        }\n" +
                                "    }\n" +
                                "}\n";
        FileUtils.writeStringToFile(initScript, initScriptCode, StandardCharsets.UTF_8);
        return initScript;
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
