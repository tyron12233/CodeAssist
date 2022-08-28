package com.tyron.builder.plugin;

import com.google.common.collect.ImmutableMap;
import com.tyron.builder.BuildModule;
import com.tyron.builder.api.artifact.impl.ArtifactsImpl;
import com.tyron.builder.api.dsl.ApplicationExtension;
import com.tyron.builder.api.variant.AndroidVersion;
import com.tyron.builder.api.variant.JavaCompilation;
import com.tyron.builder.api.variant.VariantOutputConfiguration;
import com.tyron.builder.api.variant.impl.AndroidVersionImpl;
import com.tyron.builder.core.ComponentType;
import com.tyron.builder.core.ComponentTypeImpl;
import com.tyron.builder.dexing.DexingType;
import com.tyron.builder.gradle.AndroidConfig;
import com.tyron.builder.gradle.BaseExtension;
import com.tyron.builder.gradle.errors.NoOpDeprecationReporter;
import com.tyron.builder.gradle.errors.NoOpSyncIssueReporter;
import com.tyron.builder.gradle.internal.component.ApkCreationConfig;
import com.tyron.builder.gradle.internal.dependency.DexingArtifactConfiguration;
import com.tyron.builder.gradle.internal.dependency.DexingTransformKt;
import com.tyron.builder.gradle.internal.dependency.VariantDependencies;
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts;
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues;
import com.tyron.builder.gradle.internal.scope.InternalMultipleArtifactType;
import com.tyron.builder.gradle.internal.scope.Java8LangSupport;
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer;
import com.tyron.builder.gradle.internal.scope.ProjectInfo;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.gradle.internal.services.DslServicesImpl;
import com.tyron.builder.gradle.internal.services.ProjectServices;
import com.tyron.builder.gradle.internal.services.TaskCreationServices;
import com.tyron.builder.gradle.internal.tasks.DexArchiveBuilderTask;
import com.tyron.builder.gradle.internal.tasks.DexMergingAction;
import com.tyron.builder.gradle.internal.tasks.DexMergingTask;
import com.tyron.builder.gradle.options.ProjectOptions;
import com.tyron.builder.gradle.tasks.JavaCompileCreationAction;
import com.tyron.builder.internal.DependencyConfigurator;
import com.tyron.builder.internal.tasks.factory.GlobalTaskCreationConfig;
import com.tyron.builder.internal.tasks.factory.TaskFactory;
import com.tyron.builder.internal.tasks.factory.TaskFactoryImpl;
import com.tyron.builder.internal.variant.VariantPathHelper;
import com.tyron.builder.plugin.builder.ProductFlavor;
import com.tyron.builder.plugin.options.SyncOptions;
import com.tyron.builder.plugin.tasks.RunAction;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.reflect.TypeOf;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.internal.extensibility.DefaultExtraPropertiesExtension;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import kotlin.Pair;

public class CodeAssistAppPlugin implements Plugin<Project> {

    private ApkCreationConfig apkCreationConfig;
    private TaskFactory taskFactory;


    @Override
    public void apply(@NotNull Project project) {
        boolean hasJavaPlugin = hasJavaPlugin(project);
        if (!hasJavaPlugin) {
            project.getPlugins().apply("java");
        }


        DslServices dslServices;
        dslServices = new DslServicesImpl(new ProjectServices(new NoOpSyncIssueReporter(),
                new NoOpDeprecationReporter(),
                project.getObjects(),
                project.getLogger(),
                project.getProviders(),
                project.getLayout(),
                new ProjectOptions(ImmutableMap.of(), project.getProviders()),
                project.getGradle().getSharedServices(),
                8,
                new ProjectInfo(project),
                (o) -> project.file(o),
                project.getConfigurations(),
                project.getDependencies(),
                new DefaultExtraPropertiesExtension()));
        ArtifactsImpl artifacts = new ArtifactsImpl(project, "debug");
        project.getExtensions().add("artifacts", artifacts);

        apkCreationConfig = new ApkCreationConfig() {
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

            @Override
            public boolean getNeedsJavaResStreams() {
                return false;
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
                return null;
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

        DependencyConfigurator configurator = new DependencyConfigurator(project);
        configurator.configureGeneralTransforms(true);

        AppExtension codeAssist = project.getExtensions().create("codeAssist", AppExtension.class);


        createDexTasks();
        createDexMergingTasks();

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

        taskFactory.register(new JavaCompileCreationAction(
                apkCreationConfig,
                project.getObjects(),
                false
        ));
    }

    private void createDexTasks() {
        taskFactory.register(new DexArchiveBuilderTask.CreationAction(apkCreationConfig));
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