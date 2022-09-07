package com.tyron.builder.plugin;

import com.google.common.collect.ImmutableMap;
import com.tyron.builder.BuildModule;
import com.tyron.builder.api.artifact.impl.ArtifactsImpl;
import com.tyron.builder.api.dsl.AndroidSourceDirectorySet;
import com.tyron.builder.api.dsl.AndroidSourceSet;
import com.tyron.builder.api.dsl.ApkSigningConfig;
import com.tyron.builder.api.dsl.ApplicationBuildType;
import com.tyron.builder.api.dsl.ApplicationDefaultConfig;
import com.tyron.builder.api.dsl.ApplicationProductFlavor;
import com.tyron.builder.api.extension.impl.VariantApiOperationsRegistrar;
import com.tyron.builder.api.variant.AndroidVersion;
import com.tyron.builder.api.variant.ApkPackaging;
import com.tyron.builder.api.variant.Component;
import com.tyron.builder.api.variant.JavaCompilation;
import com.tyron.builder.api.variant.VariantOutputConfiguration;
import com.tyron.builder.api.variant.impl.AndroidVersionImpl;
import com.tyron.builder.api.variant.impl.BundleConfigImpl;
import com.tyron.builder.api.variant.impl.SigningConfigImpl;
import com.tyron.builder.api.variant.impl.SourcesImpl;
import com.tyron.builder.api.variant.impl.VariantOutputList;
import com.tyron.builder.core.ComponentType;
import com.tyron.builder.core.ComponentTypeImpl;
import com.tyron.builder.dexing.DexingType;
import com.tyron.builder.gradle.errors.NoOpDeprecationReporter;
import com.tyron.builder.gradle.errors.NoOpSyncIssueReporter;
import com.tyron.builder.gradle.internal.PostprocessingFeatures;
import com.tyron.builder.gradle.internal.component.ApkCreationConfig;
import com.tyron.builder.gradle.internal.component.features.AndroidResourcesCreationConfig;
import com.tyron.builder.gradle.internal.component.features.AssetsCreationConfig;
import com.tyron.builder.gradle.internal.component.features.BuildConfigCreationConfig;
import com.tyron.builder.gradle.internal.component.features.InstrumentationCreationConfig;
import com.tyron.builder.gradle.internal.component.features.ManifestPlaceholdersCreationConfig;
import com.tyron.builder.gradle.internal.component.features.RenderscriptCreationConfig;
import com.tyron.builder.gradle.internal.component.features.ResValuesCreationConfig;
import com.tyron.builder.gradle.internal.component.legacy.ModelV1LegacySupport;
import com.tyron.builder.gradle.internal.component.legacy.OldVariantApiLegacySupport;
import com.tyron.builder.gradle.internal.core.ProductFlavor;
import com.tyron.builder.gradle.internal.core.VariantSources;
import com.tyron.builder.gradle.internal.dependency.DexingArtifactConfiguration;
import com.tyron.builder.gradle.internal.dependency.DexingTransformKt;
import com.tyron.builder.gradle.internal.dependency.SourceSetManager;
import com.tyron.builder.gradle.internal.dependency.VariantDependencies;
import com.tyron.builder.gradle.internal.dsl.ApplicationExtensionImpl;
import com.tyron.builder.gradle.internal.dsl.BuildType;
import com.tyron.builder.gradle.internal.dsl.DefaultConfig;
import com.tyron.builder.gradle.internal.pipeline.TransformManager;
import com.tyron.builder.gradle.internal.plugins.AppPlugin;
import com.tyron.builder.gradle.internal.plugins.DslContainerProvider;
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts;
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues;
import com.tyron.builder.gradle.internal.scope.DelayedActionsExecutor;
import com.tyron.builder.gradle.internal.scope.Java8LangSupport;
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer;
import com.tyron.builder.gradle.internal.scope.ProjectInfo;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.gradle.internal.services.DslServicesImpl;
import com.tyron.builder.gradle.internal.services.ProjectServices;
import com.tyron.builder.gradle.internal.services.TaskCreationServices;
import com.tyron.builder.gradle.internal.services.VariantBuilderServices;
import com.tyron.builder.gradle.internal.services.VariantBuilderServicesImpl;
import com.tyron.builder.gradle.internal.tasks.DexArchiveBuilderTask;
import com.tyron.builder.gradle.internal.tasks.DexMergingAction;
import com.tyron.builder.gradle.internal.tasks.DexMergingTask;
import com.tyron.builder.gradle.internal.variant.VariantPathHelper;
import com.tyron.builder.gradle.options.ProjectOptions;
import com.tyron.builder.gradle.tasks.JavaCompileCreationAction;
import com.tyron.builder.gradle.internal.DependencyConfigurator;
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig;
import com.tyron.builder.gradle.internal.tasks.factory.TaskFactory;
import com.tyron.builder.internal.tasks.factory.TaskFactoryImpl;
import com.tyron.builder.plugin.options.SyncOptions;
import com.tyron.builder.plugin.tasks.RunAction;

import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.internal.extensibility.DefaultExtraPropertiesExtension;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import kotlin.Pair;

public class CodeAssistAppPlugin implements Plugin<Project> {

    private ApkCreationConfig apkCreationConfig;
    private TaskFactory taskFactory;

    private Project project;


    @Override
    public void apply(@NotNull Project project) {
        boolean hasJavaPlugin = hasJavaPlugin(project);

        this.project = project;

        if (true) {
            System.setProperty("ANDROID_USER_HOME", "/data/data/com.tyron.code/files/ANDROID_HOME");
            project.getPlugins().apply(AppPlugin.class);
            return;
        }

        if (!hasJavaPlugin) {
            project.getPlugins().apply("java");
        }

        DelayedActionsExecutor delayedActionsExecutor = new DelayedActionsExecutor();

        ProjectServices projectServices = new ProjectServices(new NoOpSyncIssueReporter(),
                new NoOpDeprecationReporter(),
                project.getObjects(),
                project.getLogger(),
                project.getProviders(),
                project.getLayout(),
                new ProjectOptions(ImmutableMap.of(), project.getProviders()),
                project.getGradle().getSharedServices(),
                null,
                8,
                new ProjectInfo(project),
                project::file,
                project.getConfigurations(),
                project.getDependencies(),
                new DefaultExtraPropertiesExtension());
        VariantBuilderServices variantBuilderServices = new VariantBuilderServicesImpl(projectServices);

        DslServices dslServices = new DslServicesImpl(projectServices, null, null);
        SourceSetManager sourceSetManager = new SourceSetManager(project, false, dslServices, delayedActionsExecutor);
        AndroidSourceSet mainSourceSet = sourceSetManager.setUpSourceSet("main");


        ArtifactsImpl artifacts = new ArtifactsImpl(project, "debug");
        project.getExtensions().add("artifacts", artifacts);

        apkCreationConfig = new ApkCreationConfig() {

            @Override
            public <T extends Component> T createUserVisibleVariantObject(ProjectServices projectServices,
                                                                          VariantApiOperationsRegistrar<?, ?, ?> registrar, Object any) {
                return null;
            }
            @Nullable
            @Override
            public BundleConfigImpl getBundleConfig() {
                return null;
            }

            @Override
            public boolean isAndroidTestCoverageEnabled() {
                return false;
            }

            @Nullable
            @Override
            public PostprocessingFeatures getPostProcessingFeatures() {
                return null;
            }

            @Nullable
            @Override
            public RenderscriptCreationConfig getRenderscriptCreationConfig() {
                return null;
            }

            @Nullable
            @Override
            public OldVariantApiLegacySupport getOldVariantApiLegacySupport() {
                return null;
            }

            @NotNull
            @Override
            public ModelV1LegacySupport getModelV1LegacySupport() {
                return null;
            }

            @Override
            public void publishBuildArtifacts() {

            }

            @NotNull
            @Override
            public VariantSources getVariantSources() {
                return null;
            }

            @NotNull
            @Override
            public TransformManager getTransformManager() {
                return null;
            }

            @NotNull
            @Override
            public SourcesImpl getSources() {
                return null;
            }

            @NotNull
            @Override
            public VariantOutputList getOutputs() {
                return null;
            }

            @Nullable
            @Override
            public ManifestPlaceholdersCreationConfig getManifestPlaceholdersCreationConfig() {
                return null;
            }

            @Nullable
            @Override
            public InstrumentationCreationConfig getInstrumentationCreationConfig() {
                return null;
            }

            @Nullable
            @Override
            public AndroidResourcesCreationConfig getAndroidResourcesCreationConfig() {
                return null;
            }

            @Nullable
            @Override
            public AssetsCreationConfig getAssetsCreationConfig() {
                return null;
            }

            @Nullable
            @Override
            public SigningConfigImpl getSigningConfigImpl() {
                return null;
            }

            @NotNull
            @Override
            public MapProperty<String, String> getManifestPlaceholders() {
                return null;
            }

            @NotNull
            @Override
            public Map<String, List<String>> getScopedGlslcArgs() {
                return null;
            }

            @NotNull
            @Override
            public List<String> getDefaultGlslcArgs() {
                return null;
            }

            @Override
            public boolean getNeedsMainDexListForBundle() {
                return false;
            }

            @Override
            public boolean getNeedsMergedJavaResStream() {
                return false;
            }

            @Override
            public boolean getResourcesShrink() {
                return false;
            }

            @Override
            public boolean getMinifiedEnabled() {
                return false;
            }

            @NotNull
            @Override
            public DexingType getDexingType() {
                return null;
            }

            @Override
            public boolean getIgnoreAllLibraryKeepRules() {
                return false;
            }

            @NotNull
            @Override
            public Provider<Set<String>> getIgnoredLibraryKeepRules() {
                return null;
            }

            @NotNull
            @Override
            public ListProperty<RegularFile> getProguardFiles() {
                return null;
            }

            @NotNull
            @Override
            public ApkPackaging getPackaging() {
                return null;
            }

            @Nullable
            @Override
            public BuildConfigCreationConfig getBuildConfigCreationConfig() {
                return null;
            }

            @Nullable
            @Override
            public ResValuesCreationConfig getResValuesCreationConfig() {
                return null;
            }

            @NotNull
            @Override
            public Java8LangSupport getJava8LangSupportType() {
                return Java8LangSupport.D8;
            }

            @Override
            public boolean getNeedsShrinkDesugarLibrary() {
                return false;
            }

            @Override
            public boolean isCoreLibraryDesugaringEnabled() {
                return false;
            }

            @Override
            public boolean isMultiDexEnabled() {
                return false;
            }


            @NotNull
            @Override
            public AndroidVersion getMinSdkVersionForDexing() {
                return new AndroidVersionImpl(26, null);
            }

            @NotNull
            @Override
            public String getArtifactName(@NotNull String name) {
                return null;
            }

            @NotNull
            @Override
            public FileCollection computeLocalPackagedJars() {
                return null;
            }

            @NotNull
            @Override
            public FileCollection computeLocalFileDependencies(@NotNull Predicate<File> filePredicate) {
                return null;
            }

            @Override
            public void addVariantOutput(@NotNull VariantOutputConfiguration variantOutputConfiguration,
                                         @Nullable String outputFileName) {

            }

            @NotNull
            @Override
            public JavaCompilation getJavaCompilation() {
                return null;
            }

            @Override
            public boolean getPackageJacocoRuntime() {
                return false;
            }

            @NotNull
            @Override
            public FileCollection getProvidedOnlyClasspath() {
                return null;
            }

            @NotNull
            @Override
            public FileCollection getCompileClasspath() {
                return null;
            }

            @NotNull
            @Override
            public FileCollection getJavaClasspath(@NotNull AndroidArtifacts.ConsumedConfigType configType,
                                                   @NotNull AndroidArtifacts.ArtifactType classesType,
                                                   @Nullable Object generatedBytecodeKey) {
                return null;
            }

            @NotNull
            @Override
            public TaskCreationServices getServices() {
                return null;
            }

            @NotNull
            @Override
            public MutableTaskContainer getTaskContainer() {
                return null;
            }

            @NotNull
            @Override
            public BuildFeatureValues getBuildFeatures() {
                return null;
            }

            @NotNull
            @Override
            public VariantDependencies getVariantDependencies() {
                return null;
            }

            @NotNull
            @Override
            public GlobalTaskCreationConfig getGlobal() {
                return null;
            }

            @Nullable
            @Override
            public AndroidVersion getTargetSdkVersionOverride() {
                return null;
            }

            @NotNull
            @Override
            public AndroidVersion getTargetSdkVersion() {
                return null;
            }

            @NotNull
            @Override
            public AndroidVersion getMinSdkVersion() {
                return new AndroidVersionImpl(26, null);
            }

            @NotNull
            @Override
            public Set<String> getSupportedAbis() {
                return null;
            }

            @Override
            public boolean getDebuggable() {
                return true;
            }

            @NotNull
            @Override
            public Provider<String> getNamespace() {
                return null;
            }

            @NotNull
            @Override
            public Provider<String> getApplicationId() {
                return project.provider(() -> "test");
            }

            @NotNull
            @Override
            public ComponentType getComponentType() {
                return ComponentTypeImpl.BASE_APK;
            }

            @Override
            public boolean getEmbedsMicroApp() {
                return false;
            }

            @Override
            public boolean getTestOnlyApk() {
                return false;
            }

            @Override
            public boolean getShouldPackageDesugarLibDex() {
                return false;
            }

            @Override
            public boolean getShouldPackageProfilerDependencies() {
                return false;
            }

            @NotNull
            @Override
            public List<String> getAdvancedProfilingTransforms() {
                return null;
            }

            @Nullable
            @Override
            public File getMultiDexKeepFile() {
                return null;
            }

            @Override
            public boolean getUseJacocoTransformInstrumentation() {
                return false;
            }

            @NotNull
            @Override
            public String getDirName() {
                return null;
            }

            @NotNull
            @Override
            public String getBaseName() {
                return null;
            }

            @NotNull
            @Override
            public String getDescription() {
                return null;
            }

            @NotNull
            @Override
            public List<ProductFlavor> getProductFlavorList() {
                return Collections.emptyList();
            }

            @NotNull
            @Override
            public String computeTaskName(@NotNull String prefix, @NotNull String suffix) {
                return null;
            }

            @NotNull
            @Override
            public String computeTaskName(@NotNull String prefix) {
                return null;
            }

            @NotNull
            @Override
            public ArtifactsImpl getArtifacts() {
                return artifacts;
            }

            @NotNull
            @Override
            public VariantPathHelper getPaths() {
                return null;
            }

            @NotNull
            @Override
            public String getName() {
                return null;
            }

            @Nullable
            @Override
            public String getBuildType() {
                return null;
            }

            @NotNull
            @Override
            public List<Pair<String, String>> getProductFlavors() {
                return null;
            }

            @Nullable
            @Override
            public String getFlavorName() {
                return null;
            }
        };
        taskFactory = new TaskFactoryImpl(project.getTasks());

        DexingArtifactConfiguration artifactConfiguration =
                DexingTransformKt.getDexingArtifactConfiguration(apkCreationConfig);
        artifactConfiguration.registerTransform(project.getName(),
                project.getDependencies(),
                project.files(BuildModule.getAndroidJar()),
                project.provider(() -> ""),
                SyncOptions.ErrorFormatMode.HUMAN_READABLE);

        DependencyConfigurator configurator = new DependencyConfigurator(project, projectServices);
        configurator.configureGeneralTransforms(true);
        
        DefaultConfig applicationDefaultConfig = dslServices.newDecoratedInstance(
                DefaultConfig.class, "main", dslServices);

        Container<BuildType> buildTypeContainer = new ContainerImpl<>(name ->
                dslServices.newDecoratedInstance(BuildType.class, name, dslServices, ComponentTypeImpl.BASE_APK));

        DslContainerProvider<ApplicationDefaultConfig, ApplicationBuildType, ApplicationProductFlavor, ApkSigningConfig> provider = new DslContainerProvider<>() {
            @Override
            public void lock() {

            }

            @NotNull
            @Override
            public SourceSetManager getSourceSetManager() {
                return sourceSetManager;
            }

            @NotNull
            @Override
            public NamedDomainObjectContainer<ApkSigningConfig> getSigningConfigContainer() {
                return project.container(ApkSigningConfig.class);
            }

            @NotNull
            @Override
            public NamedDomainObjectContainer<ApplicationProductFlavor> getProductFlavorContainer() {
                return project.container(ApplicationProductFlavor.class);
            }

            @NotNull
            @Override
            public NamedDomainObjectContainer<ApplicationBuildType> getBuildTypeContainer() {
                return project.container(ApplicationBuildType.class, buildTypeContainer::create);
            }

            @NotNull
            @Override
            public ApplicationDefaultConfig getDefaultConfig() {
                return applicationDefaultConfig;
            }
        };


        ApplicationExtensionImpl extension = dslServices.newDecoratedInstance(ApplicationExtensionImpl.class,
                dslServices, provider);
        project.getExtensions().add("android", extension);

        createDexTasks();
        createDexMergingTasks();

        setupTasks();

        taskFactory.configure("run", task -> {
            task.dependsOn("listResources");
        });
        taskFactory.register("listResources", task -> {
           task.doLast(t -> {
               AndroidSourceSet sourceSet =
                       sourceSetManager.getSourceSetsContainer().maybeCreate("main");
               AndroidSourceDirectorySet res = sourceSet.getRes();
               t.getLogger().lifecycle(res.toString());
           });
        });
    }

    private void setupTasks() {
        taskFactory.register("assembleDebug", it -> {
            it.dependsOn("mergeExtDexDebug", "mergeLibDexDebug");
        });
        taskFactory.configure("mergeLibDexDebug", it -> {
            it.dependsOn("mergeProjectDexDebug");
        });

        taskFactory.configure("mergeProjectDexDebug", it -> {
            it.dependsOn("dexBuilderDebug");
        });
        taskFactory.configure("dexBuilderDebug", it -> {
            it.dependsOn("compileDebugJavaWithJavac");
        });

        taskFactory.register("run", RunAction.class, new Action<RunAction>() {
            @Override
            public void execute(@NotNull RunAction runAction) {
                runAction.getOutputs().upToDateWhen(o -> false);
                runAction.dependsOn("assembleDebug");
            }
        });

        taskFactory.register(new JavaCompileCreationAction(apkCreationConfig,
                project.getObjects(),
                false));
    }

    interface Container<T extends Named> {
        T create(String name);
        T create(String name, Action<T> action);
    }

    private static class ContainerImpl<T extends Named> implements Container<T> {

        private final List<T> values = new ArrayList<>();
        private final Function<String, T> factory;

        public ContainerImpl(Function<String, T> factory) {
            this.factory = factory;
        }

        @Override
        public T create(String name) {
            return maybeCreate(name);
        }

        @Override
        public T create(String name, Action<T> action) {
            T t = maybeCreate(name);
            action.execute(t);
            return t;
        }

        private T maybeCreate(String name) {
            T result = values.stream().filter(name::equals)
                    .findAny().orElse(null);
            if (result != null) {
                return result;
            }
            T apply = factory.apply(name);
            values.add(apply);
            return apply;
        }
    }

    private void createDexTasks() {
        taskFactory.register(new DexArchiveBuilderTask.CreationAction(apkCreationConfig, null));
    }

    private void createDexMergingTasks() {
        final boolean dexingUsingArtifactTransforms = true;
        final boolean separateFileDependenciesDexingTask = false;


        taskFactory.register(new DexMergingTask.CreationAction(apkCreationConfig,
                DexMergingAction.MERGE_EXTERNAL_LIBS,
                DexingType.NATIVE_MULTIDEX,
                dexingUsingArtifactTransforms,
                separateFileDependenciesDexingTask));

        taskFactory.register(new DexMergingTask.CreationAction(apkCreationConfig,
                DexMergingAction.MERGE_PROJECT,
                DexingType.NATIVE_MULTIDEX,
                dexingUsingArtifactTransforms,
                separateFileDependenciesDexingTask));

        taskFactory.register(new DexMergingTask.CreationAction(apkCreationConfig,
                DexMergingAction.MERGE_LIBRARY_PROJECTS,
                DexingType.NATIVE_MULTIDEX,
                dexingUsingArtifactTransforms,
                separateFileDependenciesDexingTask));
    }


    private boolean hasJavaPlugin(Project project) {
        return project.getPlugins().hasPlugin("java");
    }
}