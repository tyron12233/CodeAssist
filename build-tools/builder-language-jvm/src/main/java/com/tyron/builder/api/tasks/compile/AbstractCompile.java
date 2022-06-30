package com.tyron.builder.api.tasks.compile;

import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.model.ReplacedBy;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.tasks.Classpath;
import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.OutputDirectory;
import com.tyron.builder.api.tasks.SourceTask;

import java.io.File;

public class AbstractCompile extends SourceTask {

    private final DirectoryProperty destinationDirectory;
    private FileCollection classpath;
    private String sourceCompatibility;
    private String targetCompatibility;

    public AbstractCompile() {
        this.destinationDirectory = getProject().getObjects().directoryProperty();
    }

    /**
     * Returns the classpath to use to compile the source files.
     *
     * @return The classpath.
     */
    @Classpath
    public FileCollection getClasspath() {
        return classpath;
    }

    /**
     * Sets the classpath to use to compile the source files.
     *
     * @param configuration The classpath. Must not be null, but may be empty.
     */
    public void setClasspath(FileCollection configuration) {
        this.classpath = configuration;
    }

    /**
     * Returns the directory property that represents the directory to generate the {@code .class} files into.
     *
     * @return The destination directory property.
     * @since 6.1
     */
    @OutputDirectory
    public DirectoryProperty getDestinationDirectory() {
        return destinationDirectory;
    }

    /**
     * Returns the directory to generate the {@code .class} files into.
     *
     * @return The destination directory.
     *
     * @deprecated Use {@link #getDestinationDirectory()} instead. This method will be removed in Gradle 8.0.
     */
    @ReplacedBy("destinationDirectory")
    @Deprecated
    public File getDestinationDir() {
        // Used in Kotlin plugin - needs updating there and bumping the version first. Followup with https://github.com/gradle/gradle/issues/16783
        /*DeprecationLogger.deprecateProperty(AbstractCompile.class, "destinationDir")
            .replaceWith("destinationDirectory")
            .willBeRemovedInGradle8()
            .withUpgradeGuideSection(7, "compile_task_wiring")
            .nagUser();*/

        return destinationDirectory.getAsFile().getOrNull();
    }

    /**
     * Sets the directory to generate the {@code .class} files into.
     *
     * @param destinationDir The destination directory. Must not be null.
     *
     * @deprecated Use {@link #getDestinationDirectory()}.set() instead. This method will be removed in Gradle 8.0.
     */
    @Deprecated
    public void setDestinationDir(File destinationDir) {
//        DeprecationLogger.deprecateProperty(AbstractCompile.class, "destinationDir")
//                .replaceWith("destinationDirectory")
//                .willBeRemovedInGradle8()
//                .withUpgradeGuideSection(7, "compile_task_wiring")
//                .nagUser();

        this.destinationDirectory.set(destinationDir);
    }

    /**
     * Sets the directory to generate the {@code .class} files into.
     *
     * @param destinationDir The destination directory. Must not be null.
     * @since 4.0
     *
     * @deprecated Use {@link #getDestinationDirectory()}.set() instead. This method will be removed in Gradle 8.0.
     */
    @Deprecated
    public void setDestinationDir(Provider<File> destinationDir) {
        // Used by Android plugin. Followup with https://github.com/gradle/gradle/issues/16782
        /*DeprecationLogger.deprecateProperty(AbstractCompile.class, "destinationDir")
            .replaceWith("destinationDirectory")
            .willBeRemovedInGradle8()
            .withUpgradeGuideSection(7, "compile_task_wiring")
            .nagUser();*/

//        this.destinationDirectory.set(getProject().getLayout().dir(destinationDir));
    }

    /**
     * Returns the Java language level to use to compile the source files.
     *
     * @return The source language level.
     */
    @Input
    public String getSourceCompatibility() {
        return sourceCompatibility;
    }

    /**
     * Sets the Java language level to use to compile the source files.
     *
     * @param sourceCompatibility The source language level. Must not be null.
     */
    public void setSourceCompatibility(String sourceCompatibility) {
        this.sourceCompatibility = sourceCompatibility;
    }

    /**
     * Returns the target JVM to generate the {@code .class} files for.
     *
     * @return The target JVM.
     */
    @Input
    public String getTargetCompatibility() {
        return targetCompatibility;
    }

    /**
     * Sets the target JVM to generate the {@code .class} files for.
     *
     * @param targetCompatibility The target JVM. Must not be null.
     */
    public void setTargetCompatibility(String targetCompatibility) {
        this.targetCompatibility = targetCompatibility;
    }
}
