package com.tyron.builder.model;

import com.tyron.builder.model.level2.DependencyGraphs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.Set;

/**
 * The base information for all generated artifacts
 */
public interface BaseArtifact {

    /**
     * Name of the artifact. This should match {@link ArtifactMetaData#getName()}.
     */
    @NotNull
    String getName();

    /**
     * @return the name of the task used to compile the code.
     */
    @NotNull
    String getCompileTaskName();

    /**
     * Returns the name of the task used to generate the artifact output(s).
     *
     * @return the name of the task.
     */
    @NotNull
    String getAssembleTaskName();

    /**
     * Returns the absolute path for the listing file that will get updated after each build. The
     * model file will contain deployment related information like applicationId, list of APKs.
     *
     * @return the path to a json file.
     */
    @NotNull
    String getAssembleTaskOutputListingFile();

    /**
     * Returns the folder containing the class files. This is the output of the java compilation.
     *
     * @return a folder.
     */
    @NotNull
    File getClassesFolder();

    /**
     * Folders or jars containing additional classes (e.g., R.jar or those registered by third-party
     * plugins like Kotlin).
     */
    @NotNull
    Set<File> getAdditionalClassesFolders();

    /**
     * Returns the folder containing resource files that classes form this artifact expect to find
     * on the classpath.
     */
    @NotNull
    File getJavaResourcesFolder();

    /**
     * Return the level 0-1 type dependencies
     */
    @NotNull
    Dependencies getDependencies();

    /**
     * Return the level 0-1 type dependencies.
     * @deprecated use {@link #getDependencies()}
     */
    @Deprecated
    @NotNull
    Dependencies getCompileDependencies();

    /**
     * Returns the resolved dependencies for this artifact.
     *
     * This is a composite of all the
     * dependencies for that artifact: default config + build type + flavor(s).
     *
     * @return The dependencies.
     */
    @NotNull
    DependencyGraphs getDependencyGraphs();

    /**
     * A SourceProvider specific to the variant. This can be null if there is no flavors as
     * the "variant" is equal to the build type.
     *
     * @return the variant specific source provider
     */
    @Nullable
    SourceProvider getVariantSourceProvider();

    /**
     * A SourceProvider specific to the flavor combination.
     *
     * For instance if there are 2 dimensions, then this would be Flavor1Flavor2, and would be
     * common to all variant using these two flavors and any of the build type.
     *
     * This can be null if there is less than 2 flavors.
     *
     * @return the multi flavor specific source provider
     */
    @Nullable
    SourceProvider getMultiFlavorSourceProvider();

    /**
     * Returns names of tasks that need to be run when setting up the IDE project. After these
     * tasks have run, all the generated source files etc. that the IDE needs to know about should
     * be in place.
     */
    @NotNull
    Set<String> getIdeSetupTaskNames();

    /**
     * Returns all the source folders that are generated. This is typically folders for the R,
     * the aidl classes, and the renderscript classes.
     *
     * @return a list of folders.
     * @since 1.2
     */
    @NotNull
    Collection<File> getGeneratedSourceFolders();
}