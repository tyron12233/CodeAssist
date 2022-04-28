package com.tyron.builder.initialization;

import static com.tyron.builder.util.internal.GFileUtils.canonicalize;

import com.tyron.builder.internal.SystemProperties;

import org.jetbrains.annotations.Nullable;

import java.io.File;

public class BuildLayoutParameters {

    public static final String GRADLE_USER_HOME_PROPERTY_KEY = "gradle.user.home";
    private static final File DEFAULT_GRADLE_USER_HOME = new File(
            SystemProperties.getInstance().getUserHome() + "/.gradle");

    private File gradleInstallationHomeDir;
    private File gradleUserHomeDir;
    private File projectDir;
    private File currentDir;

    public BuildLayoutParameters() {
        this(
                findGradleInstallationHomeDir(),
                findGradleUserHomeDir(),
                null,
                canonicalize(SystemProperties.getInstance().getCurrentDir())
        );
    }

    public BuildLayoutParameters(
            @Nullable File gradleInstallationHomeDir,
            File gradleUserHomeDir,
            @Nullable File projectDir,
            File currentDir
    ) {
        this.gradleUserHomeDir = gradleUserHomeDir;
        this.gradleInstallationHomeDir = gradleInstallationHomeDir;
        this.projectDir = projectDir;
        this.currentDir = currentDir;
    }

    static private File findGradleUserHomeDir() {
        String gradleUserHome = System.getProperty(GRADLE_USER_HOME_PROPERTY_KEY);
        if (gradleUserHome == null) {
            gradleUserHome = System.getenv("GRADLE_USER_HOME");
            if (gradleUserHome == null) {
                gradleUserHome = DEFAULT_GRADLE_USER_HOME.getAbsolutePath();
            }
        }
        return canonicalize(new File(gradleUserHome));
    }

    @Nullable
    static private File findGradleInstallationHomeDir() {
//        GradleInstallation gradleInstallation = CurrentGradleInstallation.get();
//        if (gradleInstallation != null) {
//            return gradleInstallation.getGradleHome();
//        }
        return null;
    }

    public BuildLayoutParameters setProjectDir(@Nullable File projectDir) {
        this.projectDir = projectDir;
        return this;
    }

    public BuildLayoutParameters setGradleUserHomeDir(File gradleUserHomeDir) {
        this.gradleUserHomeDir = gradleUserHomeDir;
        return this;
    }

    public BuildLayoutParameters setGradleInstallationHomeDir(@Nullable File gradleInstallationHomeDir) {
        this.gradleInstallationHomeDir = gradleInstallationHomeDir;
        return this;
    }

    public BuildLayoutParameters setCurrentDir(File currentDir) {
        this.currentDir = currentDir;
        return this;
    }

    public File getCurrentDir() {
        return currentDir;
    }

    @Nullable
    public File getProjectDir() {
        return projectDir;
    }

    public File getSearchDir() {
        return projectDir != null ? projectDir : currentDir;
    }

    public File getGradleUserHomeDir() {
        return gradleUserHomeDir;
    }

    @Nullable
    public File getGradleInstallationHomeDir() {
        return gradleInstallationHomeDir;
    }
}
