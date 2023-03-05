package com.tyron.builder.gradle.internal.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.gradle.api.BaseVariant;
import com.tyron.builder.gradle.api.BaseVariantOutput;
import com.tyron.builder.gradle.api.TestVariant;
import com.tyron.builder.gradle.errors.DeprecationReporter;
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.gradle.internal.variant.TestVariantData;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

/**
 * implementation of the {@link TestVariant} interface around an {@link TestVariantData} object.
 *
 * This is a wrapper around the internal data model, in order to control what is accessible
 * through the external API.
 */
public class TestVariantImpl extends ApkVariantImpl implements TestVariant {

    @NonNull
    private final TestVariantData variantData;
    @NonNull
    private final BaseVariant testedVariantData;

    @Inject
    public TestVariantImpl(
            @NonNull TestVariantData variantData,
            @NonNull ComponentCreationConfig component,
            @NonNull BaseVariant testedVariantData,
            @NonNull DslServices services,
            @NonNull ReadOnlyObjectProvider readOnlyObjectProvider,
            @NonNull NamedDomainObjectContainer<BaseVariantOutput> outputs) {
        super(component, services, readOnlyObjectProvider, outputs);
        this.variantData = variantData;
        this.testedVariantData = testedVariantData;
    }

    @Override
    @NonNull
    public TestVariantData getVariantData() {
        return variantData;
    }

    @Override
    @NonNull
    public BaseVariant getTestedVariant() {
        return testedVariantData;
    }

    @Override
    @Nullable
    public DefaultTask getConnectedInstrumentTest() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variant.getConnectedInstrumentTestProvider()",
                        "variant.getConnectedInstrumentTest()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
        throw new UnsupportedOperationException();
//        return component.getTaskContainer().getConnectedTestTask().getOrNull();
    }

    @Nullable
    @Override
    public TaskProvider<Task> getConnectedInstrumentTestProvider() {
        // Double cast needed to satisfy the compiler
        //noinspection unchecked
        throw new UnsupportedOperationException();
//        return (TaskProvider<Task>)
//                (TaskProvider<?>) component.getTaskContainer().getConnectedTestTask();
    }

    @NonNull
    @Override
    public List<? extends DefaultTask> getProviderInstrumentTests() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variant.getProviderInstrumentTestProviders()",
                        "variant.getProviderInstrumentTests()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
        throw new UnsupportedOperationException();
//        return component.getTaskContainer().getProviderTestTaskList().stream()
//                .filter(TaskProvider::isPresent)
//                .map(Provider::get)
//                .collect(Collectors.toList());
    }

    @NonNull
    @Override
    public List<TaskProvider<Task>> getProviderInstrumentTestProviders() {
        throw new UnsupportedOperationException();
//        return component.getTaskContainer().getProviderTestTaskList().stream()
//                .filter(TaskProvider::isPresent)
//                .map(
//                        taskProvider -> {
//                            // Double cast needed to satisfy the compiler
//                            //noinspection unchecked
//                            return (TaskProvider<Task>) (TaskProvider<?>) taskProvider;
//                        })
//                .collect(Collectors.toList());
    }
}
