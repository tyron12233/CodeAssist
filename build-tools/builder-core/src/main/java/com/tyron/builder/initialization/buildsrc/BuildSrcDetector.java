package com.tyron.builder.initialization.buildsrc;

import com.tyron.builder.internal.UncheckedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class BuildSrcDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildSrcDetector.class);
    private static final String[] GRADLE_BUILD_FILES = new String[] {
            "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"
    };

    public static boolean isValidBuildSrcBuild(File buildSrcDir) {
        if (!buildSrcDir.exists()) {
            return false;
        }
        if (!buildSrcDir.isDirectory()) {
            LOGGER.info("Ignoring buildSrc: not a directory.");
            return false;
        }

        for (String buildFileName : GRADLE_BUILD_FILES) {
            if (new File(buildSrcDir, buildFileName).exists()) {
                return true;
            }
        }
        if (containsFiles(new File(buildSrcDir, "src"))) {
            return true;
        }
        LOGGER.info("Ignoring buildSrc directory: does not contain 'settings.gradle[.kts]', 'build.gradle[.kts]', or a 'src' directory.");
        return false;
    }

    private static boolean containsFiles(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            return false;
        }
        try (Stream<Path> directoryContents = Files.walk(directory.toPath())) {
            return directoryContents.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }
}
