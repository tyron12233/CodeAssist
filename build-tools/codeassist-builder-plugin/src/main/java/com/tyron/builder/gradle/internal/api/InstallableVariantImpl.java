package com.tyron.builder.gradle.internal.api;

import static com.tyron.builder.gradle.internal.api.BaseVariantImpl.TASK_ACCESS_DEPRECATION_URL;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.api.artifact.Artifact;
import com.tyron.builder.gradle.api.BaseVariantOutput;
import com.tyron.builder.gradle.api.InstallableVariant;
import com.tyron.builder.gradle.errors.DeprecationReporter;
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.gradle.internal.variant.ApkVariantData;
import org.gradle.api.DefaultTask;
import org.gradle.api.Incubating;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

/**
 * Implementation of an installable variant.
 */
public abstract class InstallableVariantImpl extends AndroidArtifactVariantImpl implements InstallableVariant {

    protected InstallableVariantImpl(
            @NonNull ComponentCreationConfig component,
            @NonNull DslServices services,
            @NonNull ReadOnlyObjectProvider immutableObjectProvider,
            @NonNull NamedDomainObjectContainer<BaseVariantOutput> outputs) {
        super(component, services, immutableObjectProvider, outputs);
    }

    @NonNull
    @Override
    public abstract ApkVariantData getVariantData();

    @Override
    public DefaultTask getInstall() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variantOutput.getInstallProvider()",
                        "variantOutput.getInstall()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);

        if (component.getTaskContainer().getInstallTask() != null) {
            return component.getTaskContainer().getInstallTask().getOrNull();
        }

        return null;
    }

    @Nullable
    @Override
    public TaskProvider<Task> getInstallProvider() {
        // Double cast needed to satisfy the compiler
        //noinspection unchecked
        return (TaskProvider<Task>) (TaskProvider<?>) component.getTaskContainer().getInstallTask();
    }

    @Override
    public DefaultTask getUninstall() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variantOutput.getUninstallProvider()",
                        "variantOutput.getUninstall()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);

        if (component.getTaskContainer().getUninstallTask() != null) {
            return component.getTaskContainer().getUninstallTask().getOrNull();
        }

        return null;
    }

    @Nullable
    @Override
    public TaskProvider<Task> getUninstallProvider() {
        // Double cast needed to satisfy the compiler
        //noinspection unchecked
        return (TaskProvider<Task>)
                (TaskProvider<?>) component.getTaskContainer().getUninstallTask();
    }

    /**
     * Semi Private APIs that we share with friends until a public API is available.
     *
     * <p>Provides a facility to retrieve the final version of an artifact type.
     *
     * @param artifactType requested artifact type.
     * @return a {@see Provider} of a {@see FileCollection} for this artifact type, possibly empty.
     */
    @NonNull
    @Incubating
    public Provider<FileCollection> getFinalArtifact(
            @NonNull Artifact.Single<? extends FileSystemLocation> artifactType) {
        return component
                .getServices()
                .provider(
                        () ->
                                component
                                        .getServices()
                                        .fileCollection(
                                                component.getArtifacts().get(artifactType)));
    }
}
