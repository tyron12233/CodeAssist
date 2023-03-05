package com.tyron.builder.gradle.internal.plugins;

import com.android.annotations.NonNull;
import com.tyron.builder.api.dsl.ApplicationBuildFeatures;
import com.tyron.builder.api.dsl.ApplicationBuildType;
import com.tyron.builder.api.dsl.ApplicationDefaultConfig;
import com.tyron.builder.api.dsl.ApplicationExtension;
import com.tyron.builder.api.dsl.ApplicationProductFlavor;
import com.tyron.builder.api.dsl.SdkComponents;
import com.tyron.builder.api.extension.impl.ApplicationAndroidComponentsExtensionImpl;
import com.tyron.builder.api.extension.impl.VariantApiOperationsRegistrar;
import com.tyron.builder.api.transform.QualifiedContent;
import com.tyron.builder.api.variant.AndroidComponentsExtension;
import com.tyron.builder.api.variant.ApplicationAndroidComponentsExtension;
import com.tyron.builder.api.variant.ApplicationVariant;
import com.tyron.builder.api.variant.ApplicationVariantBuilder;
import com.tyron.builder.gradle.BaseExtension;
import com.tyron.builder.gradle.api.BaseVariantOutput;
import com.tyron.builder.gradle.internal.AbstractAppTaskManager;
import com.tyron.builder.gradle.internal.ExtraModelInfo;
import com.tyron.builder.gradle.internal.TaskManager;
import com.tyron.builder.gradle.internal.component.ApplicationCreationConfig;
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig;
import com.tyron.builder.gradle.internal.component.TestComponentCreationConfig;
import com.tyron.builder.gradle.internal.component.TestFixturesCreationConfig;
import com.tyron.builder.gradle.internal.core.dsl.ApplicationVariantDslInfo;
import com.tyron.builder.gradle.internal.dsl.ApplicationExtensionImpl;
import com.tyron.builder.gradle.internal.dsl.BaseAppModuleExtension;
import com.tyron.builder.gradle.internal.dsl.BuildType;
import com.tyron.builder.gradle.internal.dsl.DefaultConfig;
import com.tyron.builder.gradle.internal.dsl.ProductFlavor;
import com.tyron.builder.gradle.internal.dsl.SigningConfig;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.gradle.internal.services.VersionedSdkLoaderService;
import com.tyron.builder.gradle.internal.tasks.ApplicationTaskManager;
import com.tyron.builder.gradle.internal.tasks.factory.BootClasspathConfig;
import com.tyron.builder.gradle.internal.tasks.factory.BootClasspathConfigImpl;
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig;
import com.tyron.builder.gradle.internal.tasks.factory.TaskManagerConfig;
import com.tyron.builder.gradle.internal.variant.ApplicationVariantFactory;
import com.tyron.builder.gradle.internal.variant.ComponentInfo;
import com.tyron.builder.gradle.internal.variant.VariantModel;
import com.tyron.builder.gradle.options.BooleanOption;
import com.tyron.builder.model.v2.ide.ProjectType;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import javax.inject.Inject;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.reflect.TypeOf;
import org.gradle.build.event.BuildEventsListenerRegistry;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;
import org.jetbrains.annotations.NotNull;

/** Gradle plugin class for 'application' projects, applied on the base application module */
public class AppPlugin
        extends AbstractAppPlugin<
        ApplicationBuildFeatures,
        ApplicationBuildType,
        ApplicationDefaultConfig,
        ApplicationProductFlavor,
        com.tyron.builder.api.dsl.ApplicationExtension,
        ApplicationAndroidComponentsExtension,
        ApplicationVariantBuilder,
        ApplicationVariantDslInfo,
        ApplicationCreationConfig,
        ApplicationVariant> {

    @Inject
    public AppPlugin(
            ToolingModelBuilderRegistry registry,
            SoftwareComponentFactory componentFactory,
            BuildEventsListenerRegistry listenerRegistry) {
        super(registry, componentFactory, listenerRegistry);
    }

    @NotNull
    @Override
    protected ProjectType getProjectTypeV2() {
        return ProjectType.APPLICATION;
    }

    @Override
    protected void pluginSpecificApply(@NonNull Project project) {
    }



    @NotNull
    @Override
    protected ExtensionData<ApplicationBuildFeatures, ApplicationBuildType,
            ApplicationDefaultConfig, ApplicationProductFlavor, ApplicationExtension> createExtension(
            @NotNull DslServices dslServices,
            @NotNull DslContainerProvider<DefaultConfig, BuildType, ProductFlavor, SigningConfig> dslContainers,
            @NotNull NamedDomainObjectContainer<BaseVariantOutput> buildOutputs,
            @NotNull ExtraModelInfo extraModelInfo,
            @NotNull VersionedSdkLoaderService versionedSdkLoaderService) {
        ApplicationExtensionImpl applicationExtension =
                dslServices.newDecoratedInstance(ApplicationExtensionImpl.class, dslServices, dslContainers);
        // detects whether we are running the plugin under unit test mode
        boolean forUnitTesting = project.hasProperty("_agp_internal_test_mode_");

        BootClasspathConfigImpl bootClasspathConfig =
                new BootClasspathConfigImpl(
                        project,
                        getProjectServices(),
                        versionedSdkLoaderService,
                        applicationExtension,
                        forUnitTesting);
        if (getProjectServices().getProjectOptions().get(BooleanOption.USE_NEW_DSL_INTERFACES)) {
            // noinspection unchecked,rawtypes: Hacks to make the parameterized types make sense
            Class<ApplicationExtension> instanceType = (Class) BaseAppModuleExtension.class;
            BaseAppModuleExtension android =
                    (BaseAppModuleExtension)
                            project.getExtensions()
                                    .create(
                                            new TypeOf<ApplicationExtension>() {},
                                            "android",
                                            instanceType,
                                            dslServices,
                                            bootClasspathConfig,
                                            buildOutputs,
                                            dslContainers.getSourceSetManager(),
                                            extraModelInfo,
                                            applicationExtension);
            project.getExtensions()
                    .add(
                            BaseAppModuleExtension.class,
                            "_internal_legacy_android_extension",
                            android);

            initExtensionFromSettings(applicationExtension);
            return new ExtensionData<>(android, applicationExtension, bootClasspathConfig);
        }

        BaseAppModuleExtension android =
                project.getExtensions()
                        .create(
                                "android",
                                BaseAppModuleExtension.class,
                                dslServices,
                                bootClasspathConfig,
                                buildOutputs,
                                dslContainers.getSourceSetManager(),
                                extraModelInfo,
                                applicationExtension);
        return new ExtensionData<>(android, applicationExtension, bootClasspathConfig);
    }

    @NonNull
    @Override
    protected ApplicationVariantFactory createVariantFactory() {
        return new ApplicationVariantFactory(getDslServices());
    }

    @NotNull
    @Override
    protected TaskManager<ApplicationVariantBuilder, ApplicationCreationConfig> createTaskManager(@NotNull Project project,
                                                                                                  @NotNull Collection<? extends ComponentInfo<ApplicationVariantBuilder, ApplicationCreationConfig>> variants,
                                                                                                  @NotNull Collection<? extends TestComponentCreationConfig> testComponents,
                                                                                                  @NotNull Collection<? extends TestFixturesCreationConfig> testFixturesComponents,
                                                                                                  @NotNull GlobalTaskCreationConfig globalTaskCreationConfig,
                                                                                                  @NotNull TaskManagerConfig localConfig,
                                                                                                  @NotNull BaseExtension extension) {
        return new ApplicationTaskManager(project,
                variants,
                testComponents,
                testFixturesComponents,
                globalTaskCreationConfig,
                localConfig,
                extension);
    }
}
