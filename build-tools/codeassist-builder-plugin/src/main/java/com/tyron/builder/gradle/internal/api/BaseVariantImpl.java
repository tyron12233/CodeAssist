package com.tyron.builder.gradle.internal.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.api.artifact.SingleArtifact;
import com.tyron.builder.api.component.impl.ComponentUtils;
import com.tyron.builder.api.variant.ResValue;
import com.tyron.builder.api.variant.impl.ConfigurableFileTreeBasedDirectoryEntryImpl;
import com.tyron.builder.api.variant.impl.ResValueKeyImpl;
import com.tyron.builder.api.variant.impl.TaskProviderBasedDirectoryEntryImpl;
import com.tyron.builder.gradle.api.BaseVariant;
import com.tyron.builder.gradle.api.BaseVariantOutput;
import com.tyron.builder.gradle.api.JavaCompileOptions;
import com.tyron.builder.gradle.api.SourceKind;
import com.tyron.builder.gradle.errors.DeprecationReporter;
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig;
import com.tyron.builder.gradle.internal.component.ConsumableCreationConfig;
import com.tyron.builder.gradle.internal.component.legacy.OldVariantApiLegacySupport;
import com.tyron.builder.gradle.internal.core.InternalBaseVariant;
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts;
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.gradle.internal.tasks.factory.TaskFactoryUtils;
import com.tyron.builder.gradle.internal.variant.BaseVariantData;
import com.tyron.builder.gradle.options.BooleanOption;
import com.tyron.builder.gradle.tasks.AidlCompile;
//import com.tyron.builder.gradle.tasks.ExternalNativeBuildTask;
//import com.tyron.builder.gradle.tasks.GenerateBuildConfig;
//import com.tyron.builder.gradle.tasks.MergeResources;
//import com.tyron.builder.gradle.tasks.MergeSourceSetFolders;
//import com.tyron.builder.gradle.tasks.RenderscriptCompile;
import com.tyron.builder.errors.IssueReporter;
import com.tyron.builder.model.BuildType;
import com.tyron.builder.model.ProductFlavor;
import com.tyron.builder.model.SourceProvider;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.DomainObjectCollection;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.resources.TextResource;
import org.gradle.api.tasks.AbstractCopyTask;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.api.tasks.compile.JavaCompile;

/**
 * Base class for variants.
 *
 * <p>This is a wrapper around the internal data model, in order to control what is accessible
 * through the external API.
 */
public abstract class BaseVariantImpl implements BaseVariant, InternalBaseVariant {

    public static final String TASK_ACCESS_DEPRECATION_URL =
            "https://d.android.com/r/tools/task-configuration-avoidance";

    // TODO : b/142687686
    public static final String USE_PROPERTIES_DEPRECATION_URL =
            "https://d.android.com/r/tools/use-properties";

    @NonNull protected final ComponentCreationConfig component;
    @NonNull protected final DslServices services;

    @NonNull protected final ReadOnlyObjectProvider readOnlyObjectProvider;

    @NonNull protected final NamedDomainObjectContainer<BaseVariantOutput> outputs;

    @NonNull private final OldVariantApiLegacySupport oldVariantApiLegacySupport;

    BaseVariantImpl(
            @NonNull ComponentCreationConfig component,
            @NonNull DslServices services,
            @NonNull ReadOnlyObjectProvider readOnlyObjectProvider,
            @NonNull NamedDomainObjectContainer<BaseVariantOutput> outputs) {
        this.component = component;
        this.services = services;
        this.readOnlyObjectProvider = readOnlyObjectProvider;
        this.outputs = outputs;
        this.oldVariantApiLegacySupport = component.getOldVariantApiLegacySupport();
    }

    @NonNull
    protected abstract BaseVariantData getVariantData();

    public void addOutputs(@NonNull List<BaseVariantOutput> outputs) {
       this.outputs.addAll(outputs);
    }

    @Override
    @NonNull
    public String getName() {
        return component.getName();
    }

    @Override
    @NonNull
    public String getDescription() {
        return component.getDescription();
    }

    @Override
    @NonNull
    public String getDirName() {
        return component.getDirName();
    }

    @Override
    @NonNull
    public String getBaseName() {
        return component.getBaseName();
    }

    @NonNull
    @Override
    public String getFlavorName() {
        return component.getFlavorName();
    }

    @NonNull
    @Override
    public DomainObjectCollection<BaseVariantOutput> getOutputs() {
        return outputs;
    }

    @Override
    @NonNull
    public BuildType getBuildType() {
        // this is to be removed when we can get rid of the old API.
        return readOnlyObjectProvider.getBuildType(
                (BuildType) oldVariantApiLegacySupport.getBuildTypeObj());
    }

    @Override
    @NonNull
    public List<ProductFlavor> getProductFlavors() {
        List<ProductFlavor> flavors =
                oldVariantApiLegacySupport.getProductFlavorList().stream()
                        .map(it -> (ProductFlavor) it)
                        .collect(Collectors.toList());
        return new ImmutableFlavorList(flavors, readOnlyObjectProvider);
    }

    @Override
    @NonNull
    public MergedFlavor getMergedFlavor() {
        // this is to be removed when we can get rid of the old API.
        return oldVariantApiLegacySupport.getMergedFlavor();
    }

    @NonNull
    @Override
    public JavaCompileOptions getJavaCompileOptions() {
        return oldVariantApiLegacySupport.getOldVariantApiJavaCompileOptions();
    }

    @NonNull
    @Override
    public List<SourceProvider> getSourceSets() {
        return component.getVariantSources().getSortedSourceProviders(true);
    }

    @NonNull
    @Override
    public List<ConfigurableFileTree> getSourceFolders(@NonNull SourceKind folderType) {
        switch (folderType) {
            case JAVA:
                return component.getSources().getJava().getAsFileTrees$codeassist_builder_plugin().get();
            default:
                services.getIssueReporter()
                        .reportError(
                                IssueReporter.Type.GENERIC,
                                "Unknown SourceKind value: " + folderType);
        }

        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Configuration getCompileConfiguration() {
        return component.getVariantDependencies().getCompileClasspath();
    }

    @NonNull
    @Override
    public Configuration getRuntimeConfiguration() {
        return component.getVariantDependencies().getRuntimeClasspath();
    }

    @NonNull
    @Override
    public Configuration getAnnotationProcessorConfiguration() {
        return component.getVariantDependencies().getAnnotationProcessorConfiguration();
    }

    @Override
    @NonNull
    public String getApplicationId() {
        // this getter cannot work for dynamic features as the applicationId comes from somewhere
        // else and cannot be known at config time.
        if (component.getComponentType().isDynamicFeature()) {
            services.getIssueReporter()
                    .reportError(
                            IssueReporter.Type.GENERIC,
                            "variant.getApplicationId() is not supported by dynamic-feature plugins as it cannot handle delayed setting of the application ID. Please use getApplicationIdTextResource() instead.");
        }
        if (!services.getProjectOptions().get(BooleanOption.ENABLE_LEGACY_API)) {
            services.getIssueReporter()
                    .reportError(
                            IssueReporter.Type.GENERIC,
                            new RuntimeException(
                                    "Access to applicationId via deprecated Variant API requires compatibility mode.\n"
                                                + ComponentUtils.getENABLE_LEGACY_API()));
            // return default value during sync
            return "";
        }

        return component.getApplicationId().get();
    }

    @Override
    @NonNull
    public TextResource getApplicationIdTextResource() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "ApplicationVariant.applicationId",
                        "BaseVariant.getApplicationIdTextResource",
                        "TBD",
                        DeprecationReporter.DeprecationTarget.VERSION_9_0);
        return getVariantData().applicationIdTextResource;
    }

    @Override
    @NonNull
    public Task getPreBuild() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variant.getPreBuildProvider()",
                        "variant.getPreBuild()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
        return component.getTaskContainer().getPreBuildTask().get();
    }

    @NonNull
    @Override
    public TaskProvider<Task> getPreBuildProvider() {
        //noinspection unchecked
        return (TaskProvider<Task>) component.getTaskContainer().getPreBuildTask();
    }

    @Override
    @NonNull
    public Task getCheckManifest() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variant.getCheckManifestProvider()",
                        "variant.getCheckManifest()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
        return component.getTaskContainer().getCheckManifestTask().get();
    }

    @NonNull
    @Override
    public TaskProvider<Task> getCheckManifestProvider() {
        // Double cast needed to satisfy the compiler
        //noinspection unchecked
        return (TaskProvider<Task>)
                (TaskProvider<?>) component.getTaskContainer().getCheckManifestTask();
    }

//    @Override
//    @Nullable
//    public AidlCompile getAidlCompile() {
//        if (!component.getBuildFeatures().getAidl()) {
//            services.getIssueReporter()
//                    .reportError(
//                            IssueReporter.Type.GENERIC,
//                            "aidl support is disabled via buildFeatures.");
//            return null;
//        }
//
//        services.getDeprecationReporter()
//                .reportDeprecatedApi(
//                        "variant.getAidlCompileProvider()",
//                        "variant.getAidlCompile()",
//                        TASK_ACCESS_DEPRECATION_URL,
//                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
//        return component.getTaskContainer().getAidlCompileTask().get();
//    }
//
//    @Nullable
//    @Override
//    public TaskProvider<AidlCompile> getAidlCompileProvider() {
//        if (!component.getBuildFeatures().getAidl()) {
//            services.getIssueReporter()
//                    .reportError(
//                            IssueReporter.Type.GENERIC,
//                            "aidl support is disabled via buildFeatures.");
//            return null;
//        }
//
//        //noinspection unchecked
//        return (TaskProvider<AidlCompile>) component.getTaskContainer().getAidlCompileTask();
//    }
//
//    @Override
//    @Nullable
//    public RenderscriptCompile getRenderscriptCompile() {
//        if (!component.getBuildFeatures().getRenderScript()) {
//            services.getIssueReporter()
//                    .reportError(
//                            IssueReporter.Type.GENERIC,
//                            "renderscript support is disabled via buildFeatures.");
//            return null;
//        }
//
//        services.getDeprecationReporter()
//                .reportDeprecatedApi(
//                        "variant.getRenderscriptCompileProvider()",
//                        "variant.getRenderscriptCompile()",
//                        TASK_ACCESS_DEPRECATION_URL,
//                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
//        return component.getTaskContainer().getRenderscriptCompileTask().get();
//    }
//
//    @Nullable
//    @Override
//    public TaskProvider<RenderscriptCompile> getRenderscriptCompileProvider() {
//        if (!component.getBuildFeatures().getRenderScript()) {
//            services.getIssueReporter()
//                    .reportError(
//                            IssueReporter.Type.GENERIC,
//                            "renderscript support is disabled via buildFeatures.");
//            return null;
//        }
//
//        //noinspection unchecked
//        return (TaskProvider<RenderscriptCompile>)
//                component.getTaskContainer().getRenderscriptCompileTask();
//    }
//
//    @Override
//    public MergeResources getMergeResources() {
//        services.getDeprecationReporter()
//                .reportDeprecatedApi(
//                        "variant.getMergeResourcesProvider()",
//                        "variant.getMergeResources()",
//                        TASK_ACCESS_DEPRECATION_URL,
//                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
//        return component.getTaskContainer().getMergeResourcesTask().getOrNull();
//    }
//
//    @Nullable
//    @Override
//    public TaskProvider<MergeResources> getMergeResourcesProvider() {
//        //noinspection unchecked
//        return (TaskProvider<MergeResources>) component.getTaskContainer().getMergeResourcesTask();
//    }
//
//    @Override
//    public MergeSourceSetFolders getMergeAssets() {
//        services.getDeprecationReporter()
//                .reportDeprecatedApi(
//                        "variant.getMergeAssetsProvider()",
//                        "variant.getMergeAssets()",
//                        TASK_ACCESS_DEPRECATION_URL,
//                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
//        return component.getTaskContainer().getMergeAssetsTask().getOrNull();
//    }
//
//    @Nullable
//    @Override
//    public TaskProvider<MergeSourceSetFolders> getMergeAssetsProvider() {
//        //noinspection unchecked
//        return (TaskProvider<MergeSourceSetFolders>)
//                component.getTaskContainer().getMergeAssetsTask();
//    }
//
//    @Override
//    public GenerateBuildConfig getGenerateBuildConfig() {
//        services.getDeprecationReporter()
//                .reportDeprecatedApi(
//                        "variant.getGenerateBuildConfigProvider()",
//                        "variant.getGenerateBuildConfig()",
//                        TASK_ACCESS_DEPRECATION_URL,
//                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
//        return component.getTaskContainer().getGenerateBuildConfigTask().get();
//    }
//
//    @Nullable
//    @Override
//    public TaskProvider<GenerateBuildConfig> getGenerateBuildConfigProvider() {
//        //noinspection unchecked
//        return (TaskProvider<GenerateBuildConfig>)
//                component.getTaskContainer().getGenerateBuildConfigTask();
//    }

    @Override
    @NonNull
    public JavaCompile getJavaCompile() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variant.getJavaCompileProvider()",
                        "variant.getJavaCompile()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
        return component.getTaskContainer().getJavacTask().get();
    }

    @NonNull
    @Override
    public TaskProvider<JavaCompile> getJavaCompileProvider() {
        //noinspection unchecked
        return (TaskProvider<JavaCompile>) component.getTaskContainer().getJavacTask();
    }

    @NonNull
    @Override
    public Task getJavaCompiler() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variant.getJavaCompileProvider()",
                        "variant.getJavaCompiler()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
        return component.getTaskContainer().getJavacTask().get();
    }
//
//    @NonNull
//    @Override
//    public Collection<ExternalNativeBuildTask> getExternalNativeBuildTasks() {
//        services.getDeprecationReporter()
//                .reportDeprecatedApi(
//                        "variant.getExternalNativeBuildProviders()",
//                        "variant.getExternalNativeBuildTask()",
//                        TASK_ACCESS_DEPRECATION_URL,
//                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
//
//        return getExternalNativeBuildProviders()
//                .stream()
//                .map(Provider::get)
//                .collect(Collectors.toList());
//    }
//
//    @NonNull
//    @Override
//    public Collection<TaskProvider<ExternalNativeBuildTask>> getExternalNativeBuildProviders() {
//        //noinspection unchecked
//        TaskProvider<ExternalNativeBuildTask> provider =
//                (TaskProvider<ExternalNativeBuildTask>)
//                        component.getTaskContainer().getExternalNativeBuildTask();
//        if (provider == null) {
//            return ImmutableList.of();
//        }
//
//        return ImmutableList.of(provider);
//    }

    @Nullable
    @Override
    public Task getObfuscation() {
        // This has returned null since before the TaskContainer changes.
        // This is to be removed with the old Variant API.
        return null;
    }

    @Nullable
    @Override
    public File getMappingFile() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variant.getMappingFileProvider()",
                        "variant.getMappingFile()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
        // bypass the configuration time resolution check as some calls this API during
        // configuration.
        RegularFile mappingFile =
                component
                        .getArtifacts()
                        .get(SingleArtifact.OBFUSCATION_MAPPING_FILE.INSTANCE)
                        .getOrNull();
        return mappingFile != null ? mappingFile.getAsFile() : null;
    }

    @NonNull
    @Override
    public Provider<FileCollection> getMappingFileProvider() {
        return component
                .getServices()
                .provider(
                        () ->
                                component
                                        .getServices()
                                        .fileCollection(
                                                component
                                                        .getArtifacts()
                                                        .get(
                                                                SingleArtifact
                                                                        .OBFUSCATION_MAPPING_FILE
                                                                        .INSTANCE)));
    }

    @Override
    @NonNull
    public Sync getProcessJavaResources() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variant.getProcessJavaResourcesProvider()",
                        "variant.getProcessJavaResources()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
        return component.getTaskContainer().getProcessJavaResourcesTask().get();
    }

    @NonNull
    @Override
    public TaskProvider<AbstractCopyTask> getProcessJavaResourcesProvider() {
        // Double cast needed to satisfy the compiler
        //noinspection unchecked,RedundantCast
        return (TaskProvider<AbstractCopyTask>)
                (TaskProvider<?>) component.getTaskContainer().getProcessJavaResourcesTask();
    }

    @Override
    @Nullable
    public Task getAssemble() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variant.getAssembleProvider()",
                        "variant.getAssemble()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
        return component.getTaskContainer().getAssembleTask().get();
    }

    @Nullable
    @Override
    public TaskProvider<Task> getAssembleProvider() {
        //noinspection unchecked
        return (TaskProvider<Task>) component.getTaskContainer().getAssembleTask();
    }

    @Override
    public void addJavaSourceFoldersToModel(@NonNull File... generatedSourceFolders) {
        getVariantData().addJavaSourceFoldersToModel(generatedSourceFolders);
    }

    @Override
    public void addJavaSourceFoldersToModel(@NonNull Collection<File> generatedSourceFolders) {
        getVariantData().addJavaSourceFoldersToModel(generatedSourceFolders);
    }

    @Override
    public void registerJavaGeneratingTask(@NonNull Task task, @NonNull File... sourceFolders) {
        registerJavaGeneratingTask(task, Arrays.asList(sourceFolders));
    }

    @Override
    public void registerJavaGeneratingTask(@NonNull Task task, @NonNull Collection<File> sourceFolders) {

        TaskProvider<?> taskProvider = task.getProject().getTasks().named(task.getName());
        for (File file : sourceFolders) {
            task.getOutputs().dir(file);
        }
        registerJavaGeneratingTask(taskProvider, sourceFolders);
    }

    @Override
    public void registerJavaGeneratingTask(
            @NonNull TaskProvider<? extends Task> taskProvider, @NonNull File... sourceFolders) {
        registerJavaGeneratingTask(taskProvider, Arrays.asList(sourceFolders));
    }

    @Override
    public void registerJavaGeneratingTask(
            @NonNull TaskProvider<? extends Task> taskProvider,
            @NonNull Collection<File> sourceFolders) {
        // TODO : Find a better way to express this.
        TaskFactoryUtils.dependsOn(component.getTaskContainer().sourceGenTask, taskProvider);
        for (File sourceFolder : sourceFolders) {
            DirectoryProperty directoryProperty = services.directoryProperty();
            directoryProperty.set(sourceFolder);
            Provider<Directory> mappedDirectory =
                    taskProvider.map(_task -> directoryProperty.get());

            component
                    .getSources()
                    .getJava()
                    .addSource$codeassist_builder_plugin(
                            new TaskProviderBasedDirectoryEntryImpl(
                                    "legacy_" + taskProvider.getName(),
                                    mappedDirectory,
                                    true, /* isGenerated */
                                    true, /*isUserProvided */
                                    true /* shouldBeAddedToIdeModel */));
        }
        getVariantData().registerJavaGeneratingTask(taskProvider, sourceFolders);

    }

    @Override
    public void registerExternalAptJavaOutput(@NonNull ConfigurableFileTree folder) {
        component
                .getSources()
                .getJava()
                .addSource$codeassist_builder_plugin(
                        new ConfigurableFileTreeBasedDirectoryEntryImpl("legacy_api_apt", folder));
    }

    @Override
    public void registerGeneratedResFolders(@NonNull FileCollection folders) {
        getVariantData().registerGeneratedResFolders(folders);
    }

    @Override
    @Deprecated
    public void registerResGeneratingTask(@NonNull Task task, @NonNull File... generatedResFolders) {
        getVariantData().registerResGeneratingTask(task, generatedResFolders);
    }

    @Override
    @Deprecated
    public void registerResGeneratingTask(@NonNull Task task, @NonNull Collection<File> generatedResFolders) {
        getVariantData().registerResGeneratingTask(task, generatedResFolders);
    }

    @Override
    public Object registerPreJavacGeneratedBytecode(@NonNull FileCollection fileCollection) {
        return getVariantData().registerPreJavacGeneratedBytecode(fileCollection);
    }

    @Override
    @Deprecated
    public Object registerGeneratedBytecode(@NonNull FileCollection fileCollection) {
        return registerPreJavacGeneratedBytecode(fileCollection);
    }

    @Override
    public void registerPostJavacGeneratedBytecode(@NonNull FileCollection fileCollection) {
        getVariantData().registerPostJavacGeneratedBytecode(fileCollection);
    }

    @NonNull
    @Override
    public FileCollection getCompileClasspath(@Nullable Object generatorKey) {
        return component.getJavaClasspath(
                AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH,
                AndroidArtifacts.ArtifactType.CLASSES_JAR,
                generatorKey);
    }

    @NonNull
    @Override
    public ArtifactCollection getCompileClasspathArtifacts(@Nullable Object generatorKey) {
        return component
                .getOldVariantApiLegacySupport()
                .getJavaClasspathArtifacts(
                        AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH,
                        AndroidArtifacts.ArtifactType.CLASSES_JAR,
                        generatorKey);
    }

    @Override
    public void buildConfigField(
            @NonNull String type, @NonNull String name, @NonNull String value) {
        if (component instanceof ConsumableCreationConfig) {
            component
                    .getOldVariantApiLegacySupport()
                    .addBuildConfigField(type, name, value, "Field from the variant API");
        } else {
            throw new RuntimeException(
                    "Variant "
                            + component.getComponentType().getName()
                            + " do not support adding BuildConfig fields");
        }
    }

    @Override
    public void resValue(@NonNull String type, @NonNull String name, @NonNull String value) {
        if (component instanceof ConsumableCreationConfig) {
            if (component.getResValuesCreationConfig() != null) {
                component
                        .getResValuesCreationConfig()
                        .getResValues()
                        .put(
                                new ResValueKeyImpl(type, name),
                                new ResValue(value, "Value from the variant"));
            }
        } else {
            throw new RuntimeException(
                    "Variant "
                            + component.getComponentType().getName()
                            + " do not support adding resValue");
        }
    }

    @Override
    public void missingDimensionStrategy(
            @NonNull String dimension, @NonNull String requestedValue) {
        oldVariantApiLegacySupport.handleMissingDimensionStrategy(
                dimension, ImmutableList.of(requestedValue));
    }

    @Override
    public void missingDimensionStrategy(
            @NonNull String dimension, @NonNull String... requestedValues) {
        oldVariantApiLegacySupport.handleMissingDimensionStrategy(
                dimension, ImmutableList.copyOf(requestedValues));
    }

    @Override
    public void missingDimensionStrategy(
            @NonNull String dimension, @NonNull List<String> requestedValues) {
        oldVariantApiLegacySupport.handleMissingDimensionStrategy(
                dimension, ImmutableList.copyOf(requestedValues));
    }

    @Override
    public void setOutputsAreSigned(boolean isSigned) {
        getVariantData().outputsAreSigned = isSigned;
    }

    @Override
    public boolean getOutputsAreSigned() {
        return getVariantData().outputsAreSigned;
    }

    @NonNull
    @Override
    public FileCollection getAllRawAndroidResources() {
        return oldVariantApiLegacySupport.getAllRawAndroidResources(component);
    }

    @Override
    public void register(Task task) {
        MutableTaskContainer taskContainer = component.getTaskContainer();
        TaskFactoryUtils.dependsOn(taskContainer.getAssembleTask(), task);
        TaskProvider<? extends Task> bundleTask = taskContainer.getBundleTask();
        if (bundleTask != null) {
            TaskFactoryUtils.dependsOn(bundleTask, task);
        }
        TaskProvider<? extends Zip> bundleLibraryTask = taskContainer.getBundleLibraryTask();
        if (bundleLibraryTask != null) {
            TaskFactoryUtils.dependsOn(bundleLibraryTask, task);
        }
    }
}
