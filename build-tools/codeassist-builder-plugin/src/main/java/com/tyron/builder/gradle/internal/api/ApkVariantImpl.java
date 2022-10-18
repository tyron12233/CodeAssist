package com.tyron.builder.gradle.internal.api;

import static com.tyron.builder.gradle.internal.api.BaseVariantImpl.TASK_ACCESS_DEPRECATION_URL;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.gradle.api.ApkVariant;
import com.tyron.builder.gradle.api.BaseVariantOutput;
import com.tyron.builder.gradle.errors.DeprecationReporter;
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig;
import com.tyron.builder.gradle.internal.services.DslServices;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;

/**
 * Implementation of the apk-generating variant.
 *
 * This is a wrapper around the internal data model, in order to control what is accessible
 * through the external API.
 */
public abstract class ApkVariantImpl extends InstallableVariantImpl implements ApkVariant {

    protected ApkVariantImpl(
            @NonNull ComponentCreationConfig component,
            @NonNull DslServices services,
            @NonNull ReadOnlyObjectProvider immutableObjectProvider,
            @NonNull NamedDomainObjectContainer<BaseVariantOutput> outputs) {
        super(component, services, immutableObjectProvider, outputs);
    }

    @Nullable
    @Override
    public Object getDex() {
        throw new RuntimeException("Access to the dex task is now impossible, starting with 1.4.0\n"
                + "1.4.0 introduces a new Transform API allowing manipulation of the .class files.\n"
                + "See more information: https://developer.android.com/studio/plugins/index.html");
    }

    @Nullable
    @Override
    public Task getPackageApplication() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variant.getPackageApplicationProvider()",
                        "variant.getPackageApplication()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
        return component.getTaskContainer().getPackageAndroidTask().getOrNull();
    }

    @Nullable
    @Override
    public TaskProvider<Task> getPackageApplicationProvider() {
        return (TaskProvider<Task>)
                component.getTaskContainer().getPackageAndroidTask();
    }
}
