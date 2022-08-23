package com.tyron.builder.plugin;

import static com.tyron.builder.internal.dependency.DexingTransformKt.getDexingArtifactConfigurations;

import com.tyron.builder.BuildModule;
import com.tyron.builder.api.artifact.Artifacts;
import com.tyron.builder.api.artifact.impl.ArtifactsImpl;
import com.tyron.builder.dexing.DexingType;
import com.tyron.builder.internal.DependencyConfigurator;
import com.tyron.builder.internal.component.ApkCreationConfig;
import com.tyron.builder.internal.dependency.DexingArtifactConfiguration;
import com.tyron.builder.internal.dependency.DexingTransformKt;
import com.tyron.builder.internal.publishing.AndroidArtifacts;
import com.tyron.builder.internal.scope.InternalArtifactType;
import com.tyron.builder.internal.scope.InternalMultipleArtifactType;
import com.tyron.builder.internal.tasks.DexArchiveBuilderTask;
import com.tyron.builder.internal.tasks.DexArchiveBuilderTaskKt;
import com.tyron.builder.internal.tasks.DexMergingAction;
import com.tyron.builder.internal.tasks.DexMergingTask;
import com.tyron.builder.internal.tasks.DexingExternalLibArtifactTransform;
import com.tyron.builder.internal.tasks.factory.TaskFactory;
import com.tyron.builder.internal.tasks.factory.TaskFactoryImpl;
import com.tyron.builder.internal.variant.VariantPathHelper;
import com.tyron.builder.plugin.builder.ProductFlavor;
import com.tyron.builder.plugin.options.SyncOptions;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.artifacts.transform.TransformSpec;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.FileSystemLocationProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import kotlin.Pair;
import kotlin.jvm.functions.Function1;

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