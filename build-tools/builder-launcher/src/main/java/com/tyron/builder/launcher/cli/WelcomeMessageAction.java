package com.tyron.builder.launcher.cli;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.launcher.bootstrap.ExecutionListener;
import com.tyron.builder.launcher.configuration.BuildLayoutResult;
import com.tyron.builder.util.GradleVersion;
import com.tyron.builder.util.internal.GFileUtils;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;

import javax.annotation.Nullable;

class WelcomeMessageAction implements Action<ExecutionListener> {

    private final Logger logger;
    private final BuildLayoutResult buildLayout;
    private final GradleVersion gradleVersion;
    private final Function<String, InputStream> inputStreamProvider;
    private final Action<ExecutionListener> action;

    WelcomeMessageAction(BuildLayoutResult buildLayout, Action<ExecutionListener> action) {
        this(Logging.getLogger(WelcomeMessageAction.class), buildLayout, GradleVersion.current(),
                new Function<String, InputStream>() {
                    @Nullable
                    @Override
                    public InputStream apply(@Nullable String input) {
                        return getClass().getClassLoader().getResourceAsStream(input);
                    }
                }, action);
    }

    @VisibleForTesting
    WelcomeMessageAction(Logger logger,
                         BuildLayoutResult buildLayout,
                         GradleVersion gradleVersion,
                         Function<String, InputStream> inputStreamProvider,
                         Action<ExecutionListener> action) {
        this.logger = logger;
        this.buildLayout = buildLayout;
        this.gradleVersion = gradleVersion;
        this.inputStreamProvider = inputStreamProvider;
        this.action = action;
    }

    @Override
    public void execute(ExecutionListener executionListener) {
        if (isWelcomeMessageEnabled()) {
            File markerFile = getMarkerFile();

            if (!markerFile.exists() && logger.isLifecycleEnabled()) {
                logger.lifecycle("");
                logger.lifecycle("Welcome to Gradle " + gradleVersion.getVersion() + "!");

                String featureList = readReleaseFeatures();

                if (StringUtils.isNotBlank(featureList)) {
                    logger.lifecycle("");
                    logger.lifecycle("Here are the highlights of this release:");
                    logger.lifecycle(StringUtils.stripEnd(featureList, " \n\r"));
                }

                if (!gradleVersion.isSnapshot()) {
                    logger.lifecycle("");
                    logger.lifecycle("For more details see https://docs.gradle.org/" +
                                     gradleVersion.getVersion() +
                                     "/release-notes.html");
                }

                logger.lifecycle("");

                writeMarkerFile(markerFile);
            }
        }
        action.execute(executionListener);
    }

    /**
     * The system property is set for the purpose of internal testing.
     * In user environments the system property will never be available.
     */
    private boolean isWelcomeMessageEnabled() {
        String messageEnabled =
                System.getProperty("org.gradle.internal.launcher.welcomeMessageEnabled");

        if (messageEnabled == null) {
            return true;
        }

        return Boolean.parseBoolean(messageEnabled);
    }

    private File getMarkerFile() {
        File gradleUserHomeDir = buildLayout.getGradleUserHomeDir();
        File notificationsDir = new File(gradleUserHomeDir, "notifications");
        File versionedNotificationsDir = new File(notificationsDir, gradleVersion.getVersion());
        return new File(versionedNotificationsDir, "release-features.rendered");
    }

    private String readReleaseFeatures() {
        InputStream inputStream = inputStreamProvider.apply("release-features.txt");

        if (inputStream != null) {
            StringWriter writer = new StringWriter();

            try {
                IOUtils.copy(inputStream, writer, "UTF-8");
                return writer.toString();
            } catch (IOException e) {
                // do not fail the build as feature is non-critical
            } finally {
                IOUtils.closeQuietly(inputStream);
            }
        }

        return null;
    }

    private void writeMarkerFile(File markerFile) {
        GFileUtils.mkdirs(markerFile.getParentFile());
        GFileUtils.touch(markerFile);
    }
}
