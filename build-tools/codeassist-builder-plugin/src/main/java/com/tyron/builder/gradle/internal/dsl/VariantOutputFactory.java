package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.VariantOutput;
import com.tyron.builder.api.variant.impl.VariantOutputImpl;
import com.tyron.builder.gradle.BaseExtension;
import com.tyron.builder.gradle.api.BaseVariantOutput;
import com.tyron.builder.gradle.internal.api.BaseVariantImpl;
import com.tyron.builder.gradle.internal.scope.TaskContainer;
import com.tyron.builder.gradle.internal.services.BaseServices;
import com.tyron.builder.core.ComponentType;
import com.google.common.collect.ImmutableList;

/**
 * Factory for the {@link BaseVariantOutput} for each variant output that will be added to the
 * public API
 */
public class VariantOutputFactory {

    @NonNull private final Class<? extends BaseVariantOutput> targetClass;
    @NonNull private final BaseServices services;
    @Nullable private final BaseVariantImpl deprecatedVariantPublicApi;
    @NonNull private ComponentType componentType;
    @NonNull private final TaskContainer taskContainer;
    @NonNull private final BaseExtension extension;

    public VariantOutputFactory(
            @NonNull Class<? extends BaseVariantOutput> targetClass,
            @NonNull BaseServices services,
            @NonNull BaseExtension extension,
            @Nullable BaseVariantImpl deprecatedVariantPublicApi,
            @NonNull ComponentType componentType,
            @NonNull TaskContainer taskContainer) {
        this.targetClass = targetClass;
        this.services = services;
        this.deprecatedVariantPublicApi = deprecatedVariantPublicApi;
        this.componentType = componentType;
        this.taskContainer = taskContainer;
        this.extension = extension;
    }

    public VariantOutput create(VariantOutputImpl variantApi) {
        BaseVariantOutput variantOutput =
                services.newInstance(
                        targetClass, taskContainer, services, variantApi, componentType);
        extension.getBuildOutputs().add(variantOutput);
        if (deprecatedVariantPublicApi != null) {
            deprecatedVariantPublicApi.addOutputs(ImmutableList.of(variantOutput));
        }
        return variantOutput;
    }
}
