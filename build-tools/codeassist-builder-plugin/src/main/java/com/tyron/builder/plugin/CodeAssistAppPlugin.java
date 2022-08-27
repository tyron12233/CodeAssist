package com.tyron.builder.plugin;

import com.tyron.builder.BuildModule;
import com.tyron.builder.api.artifact.impl.ArtifactsImpl;
import com.tyron.builder.api.dsl.DependenciesInfo;
import com.tyron.builder.api.variant.AndroidVersion;
import com.tyron.builder.api.variant.JavaCompilation;
import com.tyron.builder.api.variant.VariantOutputConfiguration;
import com.tyron.builder.api.variant.impl.GlobalVariantBuilderConfig;
import com.tyron.builder.api.variant.impl.GlobalVariantBuilderConfigImpl;
import com.tyron.builder.api.variant.impl.VariantBuilderImpl;
import com.tyron.builder.core.ComponentType;
import com.tyron.builder.dexing.DexingType;
import com.tyron.builder.gradle.internal.dependency.VariantDependencies;
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts;
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues;
import com.tyron.builder.gradle.internal.scope.InternalMultipleArtifactType;
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer;
import com.tyron.builder.gradle.internal.services.TaskCreationServices;
import com.tyron.builder.internal.DependencyConfigurator;
import com.tyron.builder.gradle.internal.component.ApkCreationConfig;
import com.tyron.builder.gradle.internal.dependency.DexingArtifactConfiguration;
import com.tyron.builder.gradle.internal.dependency.DexingTransformKt;
import com.tyron.builder.internal.tasks.DexArchiveBuilderTask;
import com.tyron.builder.internal.tasks.DexMergingAction;
import com.tyron.builder.internal.tasks.DexMergingTask;
import com.tyron.builder.internal.tasks.factory.GlobalTaskCreationConfig;
import com.tyron.builder.internal.tasks.factory.TaskFactory;
import com.tyron.builder.internal.tasks.factory.TaskFactoryImpl;
import com.tyron.builder.internal.variant.VariantPathHelper;
import com.tyron.builder.plugin.builder.ProductFlavor;
import com.tyron.builder.plugin.options.SyncOptions;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
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


        ArtifactsImpl artifacts = new ArtifactsImpl(project, "debug");
        project.getExtensions().add("artifacts", artifacts);


        apkCreationConfig = new ApkCreationConfig() {
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
                return null;
            }

            @NotNull
            @Override
            public Set<String> getSupportedAbis() {
                return null;
            }

            @Override
            public boolean getDebuggable() {
                return false;
            }

            @NotNull
            @Override
            public Provider<String> getNamespace() {
                return null;
            }

            @NotNull
            @Override
            public Provider<String> getApplicationId() {
                return null;
            }

            @NotNull
            @Override
            public ComponentType getComponentType() {
                return null;
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
                DexingTransformKt.getDexingArtifactConfiguration(null);
        artifactConfiguration.registerTransform(project.getName(), project.getDependencies(),
                project.files(BuildModule.getAndroidJar()), project.provider(() -> ""),
                SyncOptions.ErrorFormatMode.HUMAN_READABLE
        );

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

            it.doLast(new Action<Task>() {
                @Override
                public void execute(@NotNull Task task) {
                    System.out.println("PRINTING ALL OUTPUTSs");
                    Provider<List<Directory>> all =
                            artifacts.getAll(InternalMultipleArtifactType.DEX.INSTANCE);
                    List<Directory> directories = all.get();
                    directories.forEach(it -> System.out.println(it.getAsFile()));
                }
            });
        });
        taskFactory.configure("dexBuilderDebug", it -> {
            it.dependsOn("compileJava");
        });

    }

    private void createDexTasks() {
        taskFactory.register(new DexArchiveBuilderTask.CreationAction(apkCreationConfig));
    }

    private void createDexMergingTasks() {
        final boolean dexingUsingArtifactTransforms = true;
        final boolean separateFileDependenciesDexingTask = false;


        taskFactory.register(new DexMergingTask.CreationAction(
                apkCreationConfig,
                DexMergingAction.MERGE_EXTERNAL_LIBS,
                DexingType.NATIVE_MULTIDEX,
                dexingUsingArtifactTransforms,
                separateFileDependenciesDexingTask
        ));

        taskFactory.register(new DexMergingTask.CreationAction(
                apkCreationConfig,
                DexMergingAction.MERGE_PROJECT,
                DexingType.NATIVE_MULTIDEX,
                dexingUsingArtifactTransforms,
                separateFileDependenciesDexingTask
        ));

        taskFactory.register(new DexMergingTask.CreationAction(
                apkCreationConfig,
                DexMergingAction.MERGE_LIBRARY_PROJECTS,
                DexingType.NATIVE_MULTIDEX,
                dexingUsingArtifactTransforms,
                separateFileDependenciesDexingTask
        ));
    }



    private boolean hasJavaPlugin(Project project) {
        return project.getPlugins().hasPlugin("java");
    }
}