package com.tyron.builder.gradle.internal.ide;

import static com.android.AndroidProjectTypes.PROJECT_TYPE_LIBRARY;

import com.android.Version;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.gradle.internal.CompileOptions;
import com.tyron.builder.model.AaptOptions;
import com.tyron.builder.model.AndroidGradlePluginProjectFlags;
import com.tyron.builder.model.AndroidProject;
import com.tyron.builder.model.ArtifactMetaData;
import com.tyron.builder.model.BuildTypeContainer;
import com.tyron.builder.model.DependenciesInfo;
import com.tyron.builder.model.JavaCompileOptions;
import com.tyron.builder.model.LintOptions;
import com.tyron.builder.model.NativeToolchain;
import com.tyron.builder.model.ProductFlavorContainer;
import com.tyron.builder.model.SigningConfig;
import com.tyron.builder.model.SyncIssue;
import com.tyron.builder.model.Variant;
import com.tyron.builder.model.VariantBuildInformation;
import com.tyron.builder.model.ViewBindingOptions;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Implementation of the AndroidProject model object.
 */
final class DefaultAndroidProject implements AndroidProject, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final String name;
    @Nullable private final String groupId;
    @NonNull private final String namespace;
    @Nullable private final String androidTestNamespace;
    @NonNull
    private final String compileTarget;
    @NonNull
    private final Collection<String> bootClasspath;
    @NonNull
    private final Collection<File> frameworkSource;
    @NonNull
    private final Collection<SigningConfig> signingConfigs;
    @NonNull
    private final AaptOptions aaptOptions;
    @NonNull
    private final Collection<ArtifactMetaData> extraArtifacts;
    @NonNull
    private final Collection<SyncIssue> syncIssues;

    private final boolean baseSplit;
    private final Collection<String> dynamicFeatures;

    @NonNull
    private final JavaCompileOptions javaCompileOptions;
    @NonNull
    private final LintOptions lintOptions;
    @NonNull private final List<File> lintRuleJars;
    @NonNull private final File buildFolder;
    @NonNull
    private final String buildToolsVersion;
    @NonNull private final String ndkVersion;
    @Nullable
    private final String resourcePrefix;
    @NonNull
    private final Collection<NativeToolchain> nativeToolchains;
    private final int projectType;
    private final int apiVersion;

    @NonNull
    private final ProductFlavorContainer defaultConfig;

    private final Collection<BuildTypeContainer> buildTypes;
    private final Collection<ProductFlavorContainer> productFlavors;
    private final Collection<Variant> variants;
    private final Collection<String> variantNames;
    @Nullable private final String defaultVariant;

    @NonNull
    private final Collection<String> flavorDimensions;

    @NonNull private final ViewBindingOptions viewBindingOptions;

    @Nullable private final DependenciesInfo dependenciesInfo;

    @NonNull private final AndroidGradlePluginProjectFlags flags;

    @NonNull private final Collection<VariantBuildInformation> variantsBuildInformation;

    DefaultAndroidProject(
            @NonNull String name,
            @Nullable String groupId,
            @NonNull String namespace,
            @Nullable String androidTestNamespace,
            @NonNull ProductFlavorContainer defaultConfig,
            @NonNull Collection<String> flavorDimensions,
            @NonNull Collection<BuildTypeContainer> buildTypes,
            @NonNull Collection<ProductFlavorContainer> productFlavors,
            @NonNull Collection<Variant> variants,
            @NonNull Collection<String> variantNames,
            @Nullable String defaultVariant,
            @NonNull String compileTarget,
            @NonNull Collection<String> bootClasspath,
            @NonNull Collection<File> frameworkSource,
            @NonNull Collection<SigningConfig> signingConfigs,
            @NonNull AaptOptions aaptOptions,
            @NonNull Collection<ArtifactMetaData> extraArtifacts,
            @NonNull Collection<SyncIssue> syncIssues,
            @NonNull CompileOptions compileOptions,
            @NonNull LintOptions lintOptions,
            @NonNull List<File> lintRuleJars,
            @NonNull File buildFolder,
            @Nullable String resourcePrefix,
            @NonNull Collection<NativeToolchain> nativeToolchains,
            @NonNull String buildToolsVersion,
            @NonNull String ndkVersion,
            int projectType,
            int apiVersion,
            boolean baseSplit,
            @NonNull Collection<String> dynamicFeatures,
            @NonNull ViewBindingOptions viewBindingOptions,
            @Nullable DependenciesInfo dependenciesInfo,
            @NonNull AndroidGradlePluginProjectFlags flags,
            @NonNull Collection<VariantBuildInformation> variantsBuildInformation) {
        this.name = name;
        this.groupId = groupId;
        this.namespace = namespace;
        this.androidTestNamespace = androidTestNamespace;
        this.defaultConfig = defaultConfig;
        this.flavorDimensions = flavorDimensions;
        this.buildTypes = buildTypes;
        this.productFlavors = productFlavors;
        this.variants = variants;
        this.variantNames = variantNames;
        this.defaultVariant = defaultVariant;
        this.compileTarget = compileTarget;
        this.bootClasspath = bootClasspath;
        this.frameworkSource = frameworkSource;
        this.signingConfigs = signingConfigs;
        this.aaptOptions = aaptOptions;
        this.extraArtifacts = extraArtifacts;
        this.syncIssues = syncIssues;
        this.javaCompileOptions = new DefaultJavaCompileOptions(compileOptions);
        this.lintOptions = lintOptions;
        this.lintRuleJars = lintRuleJars;
        this.buildFolder = buildFolder;
        this.resourcePrefix = resourcePrefix;
        this.projectType = projectType;
        this.apiVersion = apiVersion;
        this.nativeToolchains = nativeToolchains;
        this.buildToolsVersion = buildToolsVersion;
        this.ndkVersion = ndkVersion;
        this.baseSplit = baseSplit;
        this.dynamicFeatures = ImmutableList.copyOf(dynamicFeatures);
        this.viewBindingOptions = viewBindingOptions;
        this.dependenciesInfo = dependenciesInfo;
        this.flags = flags;
        this.variantsBuildInformation = variantsBuildInformation;
    }

    @Override
    @NonNull
    public String getModelVersion() {
        return Version.ANDROID_GRADLE_PLUGIN_VERSION;
    }

    @Override
    public int getApiVersion() {
        return apiVersion;
    }

    @Override
    @NonNull
    public String getName() {
        return name;
    }

    @Nullable
    @Override
    public String getGroupId() {
        return groupId;
    }

    @Override
    @NonNull
    public String getNamespace() {
        return namespace;
    }

    @Override
    @Nullable
    public String getAndroidTestNamespace() {
        return androidTestNamespace;
    }

    @Override
    @NonNull
    public ProductFlavorContainer getDefaultConfig() {
        return defaultConfig;
    }

    @Override
    @NonNull
    public Collection<BuildTypeContainer> getBuildTypes() {
        return buildTypes;
    }

    @Override
    @NonNull
    public Collection<ProductFlavorContainer> getProductFlavors() {
        return productFlavors;
    }

    @Override
    @NonNull
    public Collection<Variant> getVariants() {
        return variants;
    }

    @NonNull
    @Override
    public Collection<String> getVariantNames() {
        return variantNames;
    }

    @Nullable
    @Override
    public String getDefaultVariant() {
        return defaultVariant;
    }

    @NonNull
    @Override
    public Collection<String> getFlavorDimensions() {
        return flavorDimensions;
    }

    @NonNull
    @Override
    public Collection<ArtifactMetaData> getExtraArtifacts() {
        return extraArtifacts;
    }

    @Override
    public boolean isLibrary() {
        return getProjectType() == PROJECT_TYPE_LIBRARY;
    }

    @Override
    public int getProjectType() {
        return projectType;
    }

    @Override
    @NonNull
    public String getCompileTarget() {
        return compileTarget;
    }

    @Override
    @NonNull
    public Collection<String> getBootClasspath() {
        return bootClasspath;
    }

    @Override
    @NonNull
    public Collection<File> getFrameworkSources() {
        return frameworkSource;
    }

    @Override
    @NonNull
    public Collection<SigningConfig> getSigningConfigs() {
        return signingConfigs;
    }

    @Override
    @NonNull
    public AaptOptions getAaptOptions() {
        return aaptOptions;
    }

    @Override
    @NonNull
    public LintOptions getLintOptions() {
        return lintOptions;
    }

    @Override
    @NonNull
    public Collection<String> getUnresolvedDependencies() {
        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Collection<SyncIssue> getSyncIssues() {
        return syncIssues;
    }

    @Override
    @NonNull
    public JavaCompileOptions getJavaCompileOptions() {
        return javaCompileOptions;
    }

    @Override
    @NonNull
    public File getBuildFolder() {
        return buildFolder;
    }

    @Override
    @Nullable
    public String getResourcePrefix() {
        return resourcePrefix;
    }

    @NonNull
    @Override
    public Collection<NativeToolchain> getNativeToolchains() {
        return nativeToolchains;
    }

    @NonNull
    @Override
    public String getBuildToolsVersion() {
        return buildToolsVersion;
    }

    @NonNull
    @Override
    public String getNdkVersion() {
        return ndkVersion;
    }

    @Override
    public int getPluginGeneration() {
        return GENERATION_ORIGINAL;
    }

    @Override
    public boolean isBaseSplit() {
        return baseSplit;
    }

    @NonNull
    @Override
    public Collection<String> getDynamicFeatures() {
        return dynamicFeatures;
    }

    @NonNull
    @Override
    public ViewBindingOptions getViewBindingOptions() {
        return viewBindingOptions;
    }

    @Nullable
    @Override
    public DependenciesInfo getDependenciesInfo() {
        return dependenciesInfo;
    }

    @NonNull
    @Override
    public AndroidGradlePluginProjectFlags getFlags() {
        return flags;
    }

    @NonNull
    @Override
    public Collection<VariantBuildInformation> getVariantsBuildInformation() {
        return variantsBuildInformation;
    }

    @NonNull
    @Override
    public List<File> getLintRuleJars() {
        return lintRuleJars;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultAndroidProject that = (DefaultAndroidProject) o;
        return projectType == that.projectType
                && apiVersion == that.apiVersion
                && Objects.equals(name, that.name)
                && Objects.equals(groupId, that.groupId)
                && Objects.equals(namespace, that.namespace)
                && Objects.equals(androidTestNamespace, that.androidTestNamespace)
                && Objects.equals(compileTarget, that.compileTarget)
                && Objects.equals(bootClasspath, that.bootClasspath)
                && Objects.equals(frameworkSource, that.frameworkSource)
                && Objects.equals(signingConfigs, that.signingConfigs)
                && Objects.equals(aaptOptions, that.aaptOptions)
                && Objects.equals(extraArtifacts, that.extraArtifacts)
                && Objects.equals(syncIssues, that.syncIssues)
                && Objects.equals(javaCompileOptions, that.javaCompileOptions)
                && Objects.equals(lintOptions, that.lintOptions)
                && Objects.equals(lintRuleJars, that.lintRuleJars)
                && Objects.equals(buildFolder, that.buildFolder)
                && Objects.equals(buildToolsVersion, that.buildToolsVersion)
                && Objects.equals(ndkVersion, that.ndkVersion)
                && Objects.equals(resourcePrefix, that.resourcePrefix)
                && Objects.equals(nativeToolchains, that.nativeToolchains)
                && Objects.equals(buildTypes, that.buildTypes)
                && Objects.equals(productFlavors, that.productFlavors)
                && Objects.equals(variants, that.variants)
                && Objects.equals(variantNames, that.variantNames)
                && Objects.equals(defaultVariant, that.defaultVariant)
                && Objects.equals(defaultConfig, that.defaultConfig)
                && Objects.equals(flavorDimensions, that.flavorDimensions)
                && Objects.equals(baseSplit, that.baseSplit)
                && Objects.equals(dynamicFeatures, that.dynamicFeatures)
                && Objects.equals(viewBindingOptions, that.viewBindingOptions)
                && Objects.equals(dependenciesInfo, that.dependenciesInfo)
                && Objects.equals(flags, that.flags)
                && Objects.equals(variantsBuildInformation, that.variantsBuildInformation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                name,
                groupId,
                namespace,
                androidTestNamespace,
                compileTarget,
                bootClasspath,
                frameworkSource,
                signingConfigs,
                aaptOptions,
                extraArtifacts,
                syncIssues,
                javaCompileOptions,
                lintOptions,
                lintRuleJars,
                buildFolder,
                buildToolsVersion,
                ndkVersion,
                resourcePrefix,
                nativeToolchains,
                projectType,
                apiVersion,
                buildTypes,
                productFlavors,
                variants,
                variantNames,
                defaultVariant,
                defaultConfig,
                flavorDimensions,
                baseSplit,
                dynamicFeatures,
                viewBindingOptions,
                dependenciesInfo,
                flags,
                variantsBuildInformation);
    }
}
