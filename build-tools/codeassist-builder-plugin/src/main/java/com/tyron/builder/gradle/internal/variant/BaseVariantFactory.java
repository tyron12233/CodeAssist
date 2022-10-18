package com.tyron.builder.gradle.internal.variant;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.VariantOutput;
import com.tyron.builder.api.artifact.impl.ArtifactsImpl;
import com.tyron.builder.api.component.impl.AndroidTestImpl;
import com.tyron.builder.api.component.impl.TestFixturesImpl;
import com.tyron.builder.api.dsl.CommonExtension;
import com.tyron.builder.api.variant.ComponentIdentity;
import com.tyron.builder.api.variant.VariantBuilder;
import com.tyron.builder.api.variant.impl.VariantOutputConfigurationImpl;
import com.tyron.builder.gradle.internal.BuildTypeData;
import com.tyron.builder.gradle.internal.ProductFlavorData;
import com.tyron.builder.gradle.internal.api.BaseVariantImpl;
import com.tyron.builder.gradle.internal.api.ReadOnlyObjectProvider;
import com.tyron.builder.gradle.internal.component.AndroidTestCreationConfig;
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig;
import com.tyron.builder.gradle.internal.component.TestFixturesCreationConfig;
import com.tyron.builder.gradle.internal.component.UnitTestCreationConfig;
import com.tyron.builder.gradle.internal.component.VariantCreationConfig;
import com.tyron.builder.gradle.internal.core.VariantSources;
import com.tyron.builder.gradle.internal.core.dsl.AndroidTestComponentDslInfo;
import com.tyron.builder.gradle.internal.core.dsl.TestFixturesComponentDslInfo;
import com.tyron.builder.gradle.internal.core.dsl.UnitTestComponentDslInfo;
import com.tyron.builder.gradle.internal.core.dsl.VariantDslInfo;
import com.tyron.builder.gradle.internal.dependency.VariantDependencies;
import com.tyron.builder.gradle.internal.dsl.BuildType;
import com.tyron.builder.gradle.internal.dsl.DefaultConfig;
import com.tyron.builder.gradle.internal.dsl.ProductFlavor;
import com.tyron.builder.gradle.internal.dsl.SigningConfig;
import com.tyron.builder.gradle.internal.pipeline.TransformManager;
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues;
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer;
import com.tyron.builder.gradle.internal.scope.UnitTestBuildFeatureValuesImpl;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.gradle.internal.services.TaskCreationServices;
import com.tyron.builder.gradle.internal.services.VariantServices;
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig;
import com.tyron.builder.gradle.options.BooleanOption;
import com.tyron.builder.core.BuilderConstants;
import com.tyron.builder.errors.IssueReporter;
import com.tyron.builder.errors.IssueReporter.Type;
import com.google.common.collect.ImmutableList;
import org.gradle.api.Project;

/** Common superclass for all {@link VariantFactory} implementations. */
public abstract class BaseVariantFactory<
                VariantBuilderT extends VariantBuilder,
                VariantDslInfoT extends VariantDslInfo,
                VariantT extends VariantCreationConfig>
        implements VariantFactory<VariantBuilderT, VariantDslInfoT, VariantT> {

    private static final String ANDROID_APT_PLUGIN_NAME = "com.neenbedankt.android-apt";

    @NonNull protected final DslServices dslServices;

    public BaseVariantFactory(@NonNull DslServices dslServices) {
        this.dslServices = dslServices;
    }

    @NonNull
    @Override
    public TestFixturesCreationConfig createTestFixtures(
            @NonNull ComponentIdentity componentIdentity,
            @NonNull BuildFeatureValues buildFeatures,
            @NonNull TestFixturesComponentDslInfo dslInfo,
            @NonNull VariantDependencies variantDependencies,
            @NonNull VariantSources variantSources,
            @NonNull VariantPathHelper paths,
            @NonNull ArtifactsImpl artifacts,
            @NonNull MutableTaskContainer taskContainer,
            @NonNull VariantCreationConfig mainVariant,
            @NonNull TransformManager transformManager,
            @NonNull VariantServices variantServices,
            @NonNull TaskCreationServices taskCreationServices,
            @NonNull GlobalTaskCreationConfig globalConfig) {
        TestFixturesImpl testFixturesComponent =
                dslServices.newInstance(
                        TestFixturesImpl.class,
                        componentIdentity,
                        buildFeatures,
                        dslInfo,
                        variantDependencies,
                        variantSources,
                        paths,
                        artifacts,
                        taskContainer,
                        mainVariant,
                        transformManager,
                        variantServices,
                        taskCreationServices,
                        globalConfig);
        // create default output
        String name =
                testFixturesComponent.getServices().getProjectInfo().getProjectBaseName()
                        + "-"
                        + testFixturesComponent.getBaseName()
                        + "-testFixtures."
                        + BuilderConstants.EXT_LIB_ARCHIVE;
        testFixturesComponent.addVariantOutput(
                new VariantOutputConfigurationImpl(false, ImmutableList.of()), name);
        return testFixturesComponent;
    }

    @NonNull
    @Override
    public UnitTestCreationConfig createUnitTest(
            @NonNull ComponentIdentity componentIdentity,
            @NonNull BuildFeatureValues buildFeatures,
            @NonNull UnitTestComponentDslInfo dslInfo,
            @NonNull VariantDependencies variantDependencies,
            @NonNull VariantSources variantSources,
            @NonNull VariantPathHelper paths,
            @NonNull ArtifactsImpl artifacts,
            @NonNull TestVariantData variantData,
            @NonNull MutableTaskContainer taskContainer,
            @NonNull VariantCreationConfig testedVariant,
            @NonNull TransformManager transformManager,
            @NonNull VariantServices variantServices,
            @NonNull TaskCreationServices taskCreationServices,
            @NonNull GlobalTaskCreationConfig globalConfig) {
//        UnitTestImpl unitTestProperties =
//                dslServices.newInstance(
//                        UnitTestImpl.class,
//                        componentIdentity,
//                        createUnitTestBuildFeatures(buildFeatures),
//                        dslInfo,
//                        variantDependencies,
//                        variantSources,
//                        paths,
//                        artifacts,
//                        variantData,
//                        taskContainer,
//                        testedVariant,
//                        transformManager,
//                        variantServices,
//                        taskCreationServices,
//                        globalConfig);
//
//        unitTestProperties.addVariantOutput(
//                new VariantOutputConfigurationImpl(false, ImmutableList.of()), null);
//
//        return unitTestProperties;
        return null;
    }

    @NonNull
    @Override
    public AndroidTestCreationConfig createAndroidTest(
            @NonNull ComponentIdentity componentIdentity,
            @NonNull BuildFeatureValues buildFeatures,
            @NonNull AndroidTestComponentDslInfo dslInfo,
            @NonNull VariantDependencies variantDependencies,
            @NonNull VariantSources variantSources,
            @NonNull VariantPathHelper paths,
            @NonNull ArtifactsImpl artifacts,
            @NonNull TestVariantData variantData,
            @NonNull MutableTaskContainer taskContainer,
            @NonNull VariantCreationConfig testedVariant,
            @NonNull TransformManager transformManager,
            @NonNull VariantServices variantServices,
            @NonNull TaskCreationServices taskCreationServices,
            @NonNull GlobalTaskCreationConfig globalConfig) {
        AndroidTestImpl androidTestProperties =
                dslServices.newInstance(
                        AndroidTestImpl.class,
                        componentIdentity,
                        buildFeatures,
                        dslInfo,
                        variantDependencies,
                        variantSources,
                        paths,
                        artifacts,
                        variantData,
                        taskContainer,
                        testedVariant,
                        transformManager,
                        variantServices,
                        taskCreationServices,
                        globalConfig);

        androidTestProperties.addVariantOutput(
                new VariantOutputConfigurationImpl(false, ImmutableList.of()), null);

        return androidTestProperties;
    }

    @Nullable
    @Override
    public BaseVariantImpl createVariantApi(
            @NonNull ComponentCreationConfig component,
            @NonNull BaseVariantData variantData,
            @NonNull ReadOnlyObjectProvider readOnlyObjectProvider) {
        Class<? extends BaseVariantImpl> implementationClass =
                getVariantImplementationClass();

        return dslServices.newInstance(
                implementationClass,
                variantData,
                component,
                dslServices,
                readOnlyObjectProvider,
                dslServices.domainObjectContainer(VariantOutput.class));
    }

    @Override
    public void preVariantCallback(
            @NonNull Project project,
            @NonNull CommonExtension<?, ?, ?, ?> dslExtension,
            @NonNull
                    VariantInputModel<DefaultConfig, BuildType, ProductFlavor, SigningConfig>
                            model) {
        if (project.getPluginManager().hasPlugin(ANDROID_APT_PLUGIN_NAME)) {
            dslServices
                    .getIssueReporter()
                    .reportError(
                            Type.INCOMPATIBLE_PLUGIN,
                            "android-apt plugin is incompatible with the Android Gradle plugin.  "
                                    + "Please use 'annotationProcessor' configuration "
                                    + "instead.",
                            "android-apt");
        }

        validateBuildConfig(model, dslExtension.getBuildFeatures().getBuildConfig());
        validateResValues(model, dslExtension.getBuildFeatures().getResValues());
    }

    void validateBuildConfig(
            @NonNull
                    VariantInputModel<DefaultConfig, BuildType, ProductFlavor, SigningConfig> model,
            @Nullable Boolean buildConfig) {
        if (buildConfig == null) {
            buildConfig =
                    dslServices.getProjectOptions().get(BooleanOption.BUILD_FEATURE_BUILDCONFIG);
        }

        if (!buildConfig) {
            IssueReporter issueReporter = dslServices.getIssueReporter();

            if (!model.getDefaultConfigData().getDefaultConfig().getBuildConfigFields().isEmpty()) {
                issueReporter.reportError(
                        Type.GENERIC,
                        "defaultConfig contains custom BuildConfig fields, but the feature is disabled.");
            }

            for (BuildTypeData<BuildType> buildType : model.getBuildTypes().values()) {
                if (!buildType.getBuildType().getBuildConfigFields().isEmpty()) {
                    issueReporter.reportError(
                            Type.GENERIC,
                            String.format(
                                    "Build Type '%s' contains custom BuildConfig fields, but the feature is disabled.",
                                    buildType.getBuildType().getName()));
                }
            }

            for (ProductFlavorData<ProductFlavor> productFlavor :
                    model.getProductFlavors().values()) {
                if (!productFlavor.getProductFlavor().getBuildConfigFields().isEmpty()) {
                    issueReporter.reportError(
                            Type.GENERIC,
                            String.format(
                                    "Product Flavor '%s' contains custom BuildConfig fields, but the feature is disabled.",
                                    productFlavor.getProductFlavor().getName()));
                }
            }
        }
    }

    void validateResValues(
            @NonNull
                    VariantInputModel<DefaultConfig, BuildType, ProductFlavor, SigningConfig> model,
            @Nullable Boolean resValues) {
        if (resValues == null) {
            resValues = dslServices.getProjectOptions().get(BooleanOption.BUILD_FEATURE_RESVALUES);
        }

        if (!resValues) {
            IssueReporter issueReporter = dslServices.getIssueReporter();

            if (!model.getDefaultConfigData().getDefaultConfig().getResValues().isEmpty()) {
                issueReporter.reportError(
                        Type.GENERIC,
                        "defaultConfig contains custom resource values, but the feature is disabled.");
            }

            for (BuildTypeData<BuildType> buildType : model.getBuildTypes().values()) {
                if (!buildType.getBuildType().getResValues().isEmpty()) {
                    issueReporter.reportError(
                            Type.GENERIC,
                            String.format(
                                    "Build Type '%s' contains custom resource values, but the feature is disabled.",
                                    buildType.getBuildType().getName()));
                }
            }

            for (ProductFlavorData<ProductFlavor> productFlavor :
                    model.getProductFlavors().values()) {
                if (!productFlavor.getProductFlavor().getResValues().isEmpty()) {
                    issueReporter.reportError(
                            Type.GENERIC,
                            String.format(
                                    "Product Flavor '%s' contains custom resource values, but the feature is disabled.",
                                    productFlavor.getProductFlavor().getName()));
                }
            }
        }
    }

    private BuildFeatureValues createUnitTestBuildFeatures(
            BuildFeatureValues testedVariantBuildFeatures) {
        return new UnitTestBuildFeatureValuesImpl(testedVariantBuildFeatures);
    }
}
