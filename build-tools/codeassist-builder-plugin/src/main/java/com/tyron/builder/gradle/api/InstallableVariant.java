package com.tyron.builder.gradle.api;

import com.android.annotations.Nullable;
import org.gradle.api.DefaultTask;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;

/** A Build variant that supports installation. */
@Deprecated
public interface InstallableVariant {

    /**
     * Returns the install task for the variant.
     *
     * @deprecated Use {@link #getInstallProvider()}
     */
    @Nullable
    @Deprecated
    DefaultTask getInstall();

    /**
     * Returns the {@link TaskProvider} for the install task for the variant.
     *
     * <p>Prefer this to {@link #getInstall()} as it triggers eager configuration of the task.
     */
    @Nullable
    TaskProvider<Task> getInstallProvider();

    /**
     * Returns the uninstallation task.
     *
     * <p>For non-library project this is always true even if the APK is not created because signing
     * isn't setup.
     *
     * @deprecated Use {@link #getUninstallProvider()}
     */
    @Nullable
    @Deprecated
    DefaultTask getUninstall();

    /**
     * Returns the {@link TaskProvider} for the uninstallation task.
     *
     * <p>For non-library project this is always true even if the APK is not created because signing
     * isn't setup.
     *
     * <p>Prefer this to {@link #getUninstall()} as it triggers eager configuration of the task.
     */
    @Nullable
    TaskProvider<Task> getUninstallProvider();
}
