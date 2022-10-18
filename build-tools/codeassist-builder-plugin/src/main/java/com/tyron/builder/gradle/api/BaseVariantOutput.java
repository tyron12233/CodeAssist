package com.tyron.builder.gradle.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.OutputFile;
import com.tyron.builder.gradle.tasks.ManifestProcessorTask;
import com.tyron.builder.gradle.tasks.ProcessAndroidResources;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.model.Managed;

/**
 * A Build variant output and all its public data. This is the base class for items common to apps,
 * test apps, and libraries
 */
@Deprecated
@Managed
public interface BaseVariantOutput extends OutputFile {

    /**
     * Returns the Android Resources processing task.
     *
     * @deprecated Use {@link #getProcessResourcesProvider()}
     */
    @NonNull
    @Deprecated
    ProcessAndroidResources getProcessResources();

    /**
     * Returns the {@link TaskProvider} for the Android Resources processing task.
     *
     * <p>Prefer this to {@link #getProcessResources()} as it triggers eager configuration of the
     * task.
     */
    @NonNull
    TaskProvider<ProcessAndroidResources> getProcessResourcesProvider();

    /**
     * Returns the manifest merging task.
     *
     * @deprecated Use {@link #getProcessManifestProvider()}
     */
    @NonNull
    @Deprecated
    ManifestProcessorTask getProcessManifest();

    /**
     * Returns the {@link TaskProvider} for the manifest merging task
     *
     * <p>Prefer this to {@link #getProcessManifest()} as it triggers eager configuration of the
     * task.
     */
    @NonNull
    TaskProvider<ManifestProcessorTask> getProcessManifestProvider();

    /**
     * Returns the assemble task for this particular output
     *
     * @deprecated Use {@link BaseVariant#getAssembleProvider()}
     */
    @Nullable
    @Deprecated
    Task getAssemble();

    /**
     * Returns the name of the variant. Guaranteed to be unique.
     */
    @NonNull
    String getName();

    /**
     * Returns the base name for the output of the variant. Guaranteed to be unique.
     */
    @NonNull
    String getBaseName();

    /**
     * Returns a subfolder name for the variant output. Guaranteed to be unique.
     *
     * This is usually a mix of build type and flavor(s) (if applicable).
     * For instance this could be:
     * "debug"
     * "debug/myflavor"
     * "release/Flavor1Flavor2"
     */
    @NonNull
    String getDirName();

}
