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
import org.gradle.api.artifacts.DependencySet;
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

import kotlin.Pair;
import kotlin.jvm.functions.Function1;

@SuppressWarnings("Convert2Lambda")
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
        project.getTasks().register("assembleDebug", new Action<Task>() {
            @Override
            public void execute(@NotNull Task task) {
                task.dependsOn("assemble");
            }
        });

        project.getTasks().getByName("assemble").dependsOn("package");

        project.getTasks().register("package", new Action<Task>() {
            @Override
            public void execute(@NotNull Task task) {
                TaskContainer tasks = project.getTasks();
                tasks.getByName("mergeLibDexDebug").dependsOn("mergeProjectDexDebug");
                tasks.getByName("mergeProjectDexDebug").dependsOn("mergeExtDexDebug");
                tasks.getByName("mergeExtDexDebug").dependsOn("compileDex");
                task.dependsOn("mergeLibDexDebug");
                task.getOutputs().upToDateWhen(Specs.satisfyNone());

                task.doLast(new Action<Task>() {
                    @Override
                    public void execute(@NotNull Task task) {

                    }
                });
            }
        });

        configureDexTasks(project);
        createDexMergingTasks(project);
    }

    private void configureDexTasks(Project project) {
        TaskProvider<DexArchiveBuilderTask> provider = project.getTasks()
                .register("compileDex", DexArchiveBuilderTask.class,
                        new Action<DexArchiveBuilderTask>() {
                            @Override
                            public void execute(@NotNull DexArchiveBuilderTask task) {
                                task.dependsOn(JavaPlugin.COMPILE_JAVA_TASK_NAME);

                                task.getProjectClasses().setFrom(
                                        project.getLayout().getBuildDirectory().dir("classes"));
                                task.getProjectVariant().set("debug");

                                task.getExternalLibClasses()
                                        .from(getDexForExternalLibs(task, "jar"));
                                task.getExternalLibClasses()
                                        .from(getDexForExternalLibs(task, "dir"));

                                task.getNumberOfBuckets().set(1);
                                task.getDexParams().getDebuggable().set(true);
                                task.getDexParams().getErrorFormatMode()
                                        .set(SyncOptions.ErrorFormatMode.HUMAN_READABLE);
                                task.getDexParams().getMinSdkVersion().set(21);
                                task.getDexParams().getWithDesugaring().set(false);
                            }
                        });
        ArtifactsImpl artifacts = (ArtifactsImpl) project.getExtensions().getByName("artifacts");
        artifacts.setInitialProvider(provider, it -> it.getProjectOutputs().getDex())
                .withName("out")
                .withName("out")
                .on(InternalArtifactType.PROJECT_DEX_ARCHIVE.INSTANCE);
        artifacts.setInitialProvider(provider, it -> it.getSubProjectOutputs().getDex())
                .withName("out")
                .withName("out")
                .on(InternalArtifactType.SUB_PROJECT_DEX_ARCHIVE.INSTANCE);
        artifacts.setInitialProvider(provider, it -> it.getExternalLibsOutputs().getDex())
                .withName("out")
                .on(InternalArtifactType.EXTERNAL_LIBS_DEX_ARCHIVE.INSTANCE);
        artifacts.setInitialProvider(provider, it -> it.getExternalLibsFromArtifactTransformsOutputs().getDex())
                .withName("out")
                .on(InternalArtifactType.EXTERNAL_LIBS_DEX_ARCHIVE_WITH_ARTIFACT_TRANSFORMS.INSTANCE);
        artifacts.setInitialProvider(provider, it -> it.getMixedScopeOutputs().getDex())
                .withName("out")
                .on(InternalArtifactType.MIXED_SCOPE_DEX_ARCHIVE.INSTANCE);
        artifacts.setInitialProvider(provider, DexArchiveBuilderTask::getInputJarHashesFile)
                .on(InternalArtifactType.DEX_ARCHIVE_INPUT_JAR_HASHES.INSTANCE);
        artifacts.setInitialProvider(provider, DexArchiveBuilderTask::getDesugarGraphDir)
                .on(InternalArtifactType.DESUGAR_GRAPH.INSTANCE);
        artifacts.setInitialProvider(provider, DexArchiveBuilderTask::getPreviousRunNumberOfBucketsFile)
                .on(InternalArtifactType.DEX_NUMBER_OF_BUCKETS_FILE.INSTANCE);
    }

    private void createDexMergingTasks(Project project) {
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

    private FileCollection getDexForExternalLibs(DexArchiveBuilderTask task, String inputType) {
        Configuration runtimeClasspath = task.getProject().getConfigurations()
                .getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
        task.getProject().getDependencies().registerTransform(
                DexingExternalLibArtifactTransform.class,
                new Action<TransformSpec<DexingExternalLibArtifactTransform.Parameters>>() {
                    @Override
                    public void execute(@NotNull TransformSpec<DexingExternalLibArtifactTransform.Parameters> spec) {
                        spec.parameters(it -> {
                            it.getProjectName().set(task.getProject().getName());
                            it.getMinSdkVersion().set(task.getDexParams().getMinSdkVersion());
                            it.getDebuggable().set(task.getDexParams().getDebuggable());
                            it.getBootClasspath().from(task.getDexParams().getDesugarClasspath());
                            it.getDesugaringClasspath().from();
                            it.getEnableDesugaring().set(task.getDexParams().getWithDesugaring());
                            it.getLibConfiguration().set(task.getDexParams().getCoreLibDesugarConfig());
                            it.getErrorFormat().set(task.getDexParams().getErrorFormatMode());
                        });

                        // Until Gradle provides a better way to run artifact transforms for arbitrary
                        // configuration, use "artifactType" attribute as that one is always present.
                        spec.getFrom().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, inputType);
                        // Make this attribute unique by using task name. This ensures that every task will
                        // have a unique transform to run which is required as input parameters are
                        // task-specific.
                        spec.getTo().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "ext-dex-debug");
                    }
                });

        Configuration dex = task.getProject().getConfigurations().detachedConfiguration();
        dex.getDependencies().add(
                task.getProject().getDependencies().create(
                        runtimeClasspath
                )
        );
        return dex.getIncoming().artifactView(it -> it.getAttributes().attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                "ext-dex-debug"
            )).getFiles();
    }

    private boolean hasJavaPlugin(Project project) {
        return project.getPlugins().hasPlugin("java");
    }
}