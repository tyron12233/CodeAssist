package com.tyron.builder.gradle.api;

import com.android.annotations.Nullable;
import com.tyron.builder.gradle.internal.core.InternalBaseVariant;

import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;

/** A Build variant and all its public data. */
@Deprecated
public interface ApkVariant
        extends BaseVariant, InstallableVariant, AndroidArtifactVariant, InternalBaseVariant {

    /**
     * Returns the Dex task.
     *
     * This method will actually throw an exception with a clear message.
     *
     * @deprecated  With the new transform mechanism, there is no direct access to the task anymore.
     */
    @Deprecated
    @Nullable
    Object getDex();

    /**
     * Returns the packaging tas
     *
     * @deprecated Use {@link #getPackageApplicationProvider()}
     */
    @Nullable
    @Deprecated
    Task getPackageApplication();

    /**
     * Returns the packaging task
     *
     * <p>Prefer this to {@link #getPackageApplication()} as it triggers eager configuration of the
     * task.
     */
    @Nullable
    TaskProvider<Task> getPackageApplicationProvider();

}
