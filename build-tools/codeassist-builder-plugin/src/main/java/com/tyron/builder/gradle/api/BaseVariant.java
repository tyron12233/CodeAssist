package com.tyron.builder.gradle.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.api.dsl.BuildFeatures;
import com.tyron.builder.api.variant.Component;
import com.tyron.builder.api.variant.SourceDirectories;
import com.tyron.builder.api.variant.Sources;
//import com.tyron.builder.gradle.tasks.AidlCompile;
//import com.tyron.builder.gradle.tasks.ExternalNativeBuildTask;
//import com.tyron.builder.gradle.tasks.GenerateBuildConfig;
//import com.tyron.builder.gradle.tasks.MergeResources;
//import com.tyron.builder.gradle.tasks.MergeSourceSetFolders;
//import com.tyron.builder.gradle.tasks.RenderscriptCompile;
import com.tyron.builder.model.BuildType;
import com.tyron.builder.model.ProductFlavor;
import com.tyron.builder.model.SourceProvider;
import java.io.File;
import java.util.Collection;
import java.util.List;
import org.gradle.api.DomainObjectCollection;
import org.gradle.api.Incubating;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.resources.TextResource;
import org.gradle.api.tasks.AbstractCopyTask;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

/**
 * A Build variant and all its public data. This is the base class for items common to apps, test
 * apps, and libraries
 */
@Deprecated
public interface BaseVariant {

    /**
     * Returns the name of the variant. Guaranteed to be unique.
     */
    @NonNull
    String getName();

    /**
     * Returns a description for the build variant.
     */
    @NonNull
    String getDescription();

    /**
     * Returns a subfolder name for the variant. Guaranteed to be unique.
     *
     * This is usually a mix of build type and flavor(s) (if applicable).
     * For instance this could be:
     * "debug"
     * "debug/myflavor"
     * "release/Flavor1Flavor2"
     */
    @NonNull
    String getDirName();

    /**
     * Returns the base name for the output of the variant. Guaranteed to be unique.
     */
    @NonNull
    String getBaseName();

    /**
     * Returns the flavor name of the variant. This is a concatenation of all the
     * applied flavors
     * @return the name of the flavors, or an empty string if there is not flavors.
     */
    @NonNull
    String getFlavorName();

    /**
     * Returns the variant outputs. There should always be at least one output.
     *
     * @return a non-null list of variants.
     */
    @NonNull
    DomainObjectCollection<BaseVariantOutput> getOutputs();

    /** Returns the {@link BuildType} for this build variant. */
    @NonNull
    BuildType getBuildType();

    /**
     * Returns a {@link ProductFlavor} that represents the merging of the default config and the
     * flavors of this build variant.
     */
    @NonNull
    ProductFlavor getMergedFlavor();

    /**
     * Returns a {@link JavaCompileOptions} that represents the java compile settings for this build
     * variant.
     */
    @NonNull
    JavaCompileOptions getJavaCompileOptions();

    /**
     * Returns the list of {@link ProductFlavor} for this build variant.
     *
     * <p>This is always non-null but could be empty.
     */
    @NonNull
    List<ProductFlavor> getProductFlavors();

    /**
     * Returns a list of sorted SourceProvider in order of ascending order, meaning, the earlier
     * items are meant to be overridden by later items.
     *
     * @return a list of source provider
     */
    @NonNull
    List<SourceProvider> getSourceSets();

    /**
     * Returns a list of FileCollection representing the source folders.
     *
     * <p>You can replace calls to this method by using {@link Component#getSources()}, then use the
     * {@link Sources#getJava()} and then {@link SourceDirectories.Flat#getAll()}.
     *
     * @param folderType the type of folder to return.
     * @return a list of folder + dependency as file collections.
     */
    @NonNull
    List<ConfigurableFileTree> getSourceFolders(@NonNull SourceKind folderType);

    /** Returns the configuration object for the compilation */
    @NonNull
    Configuration getCompileConfiguration();

    /** Returns the configuration object for the annotation processor. */
    @NonNull
    Configuration getAnnotationProcessorConfiguration();

    /** Returns the configuration object for the runtime */
    @NonNull
    Configuration getRuntimeConfiguration();

    /** Returns the applicationId of the variant. */
    @NonNull
    String getApplicationId();

    /**
     * Returns the true application Id of the variant. For feature variants, this returns the
     * resolved application id from the application. For application variants, this is the same as
     * getApplicationId.
     *
     * @deprecated use {@link ApplicationVariants#getApplicationId()}.
     */
    @NonNull
    @Deprecated
    TextResource getApplicationIdTextResource();

    /**
     * Returns the pre-build anchor task
     *
     * @deprecated Use {@link #getPreBuildProvider()}
     */
    @NonNull
    @Deprecated
    Task getPreBuild();

    /**
     * Returns the {@link TaskProvider} for the pre-build anchor task.
     *
     * <p>Prefer this to {@link #getPreBuild()} as it triggers eager configuration of the task.
     */
    @NonNull
    TaskProvider<Task> getPreBuildProvider();

    /**
     * Returns the check manifest task.
     *
     * @deprecated Use {@link #getCheckManifestProvider()}
     */
    @NonNull
    @Deprecated
    Task getCheckManifest();

    /**
     * Returns the {@link TaskProvider} for the check manifest task.
     *
     * <p>Prefer this to {@link #getCheckManifest()} as it triggers eager configuration of the task.
     */
    @NonNull
    TaskProvider<Task> getCheckManifestProvider();
//
//    /**
//     * Returns the AIDL compilation task.
//     *
//     * <p>If aidl feature is disabled via {@link BuildFeatures#getAidl()} this will throw an
//     * exception (or return <code>null</code> during sync.)
//     *
//     * @deprecated Use {@link #getAidlCompileProvider()}
//     */
//    @Nullable
//    @Deprecated
//    AidlCompile getAidlCompile();
//
//    /**
//     * Returns the {@link TaskProvider} for the AIDL compilation task.
//     *
//     * <p>If aidl feature is disabled via {@link BuildFeatures#getAidl()} this will throw an
//     * exception (or return <code>null</code> during sync.)
//     *
//     * <p>Prefer this to {@link #getAidlCompile()} as it triggers eager configuration of the task.
//     */
//    @Nullable
//    TaskProvider<AidlCompile> getAidlCompileProvider();
//
//    /**
//     * Returns the Renderscript compilation task.
//     *
//     * <p>If renderscript feature is disabled via {@link BuildFeatures#getRenderScript()} this will
//     * throw an exception (or return <code>null</code> during sync.)
//     *
//     * @deprecated Use {@link #getRenderscriptCompileProvider()}
//     */
//    @Nullable
//    @Deprecated
//    RenderscriptCompile getRenderscriptCompile();
//
//    /**
//     * Returns the {@link TaskProvider} for the Renderscript compilation task.
//     *
//     * <p>Prefer this to {@link #getRenderscriptCompile()} as it triggers eager configuration of the
//     * task.
//     *
//     * <p>If renderscript feature is disabled via {@link BuildFeatures#getRenderScript()} this will
//     * throw an exception (or return <code>null</code> during sync.)
//     */
//    @Nullable
//    TaskProvider<RenderscriptCompile> getRenderscriptCompileProvider();
//
//    /**
//     * Returns the resource merging task.
//     *
//     * @deprecated Use {@link #getMergeResourcesProvider()}
//     */
//    @Nullable
//    @Deprecated
//    MergeResources getMergeResources();
//
//    /**
//     * Returns the {@link TaskProvider} for the resource merging task.
//     *
//     * <p>Prefer this to {@link #getMergeResources()} as it triggers eager configuration of the
//     * task.
//     */
//    @Nullable
//    TaskProvider<MergeResources> getMergeResourcesProvider();
//
//    /**
//     * Returns the asset merging task.
//     *
//     * @deprecated Use {@link #getMergeAssetsProvider()}
//     */
//    @Nullable
//    @Deprecated
//    MergeSourceSetFolders getMergeAssets();
//
//    /**
//     * Returns the {@link TaskProvider} for the asset merging task.
//     *
//     * <p>Prefer this to {@link #getMergeAssets()} as it triggers eager configuration of the task.
//     */
//    @Nullable
//    TaskProvider<MergeSourceSetFolders> getMergeAssetsProvider();
//
//    /**
//     * Returns the BuildConfig generation task.
//     *
//     * @deprecated Use {@link #getGenerateBuildConfigProvider()}
//     */
//    @Nullable
//    @Deprecated
//    GenerateBuildConfig getGenerateBuildConfig();
//
//    /**
//     * Returns the {@link TaskProvider} for the BuildConfig generation task.
//     *
//     * <p>Prefer this to {@link #getGenerateBuildConfig()} as it triggers eager configuration of the
//     * task.
//     */
//    @Nullable
//    TaskProvider<GenerateBuildConfig> getGenerateBuildConfigProvider();

    /**
     * Returns the Java Compilation task
     *
     * @deprecated Use {@link #getJavaCompileProvider()}
     */
    @NonNull
    @Deprecated
    JavaCompile getJavaCompile();

    /**
     * Returns the {@link TaskProvider} for the Java Compilation task
     *
     * <p>Prefer this to {@link #getJavaCompile()} as it triggers eager configuration of the task.
     */
    @NonNull
    TaskProvider<JavaCompile> getJavaCompileProvider();

    /**
     * Returns the Java Compiler task.
     *
     * @deprecated Use {@link #getJavaCompileProvider()}
     */
    @NonNull
    @Deprecated
    Task getJavaCompiler();

    /**
     * Returns the java compilation classpath.
     *
     * <p>The provided key allows controlling how much of the classpath is returned.
     *
     * <ul>
     *   <li>if <code>null</code>, the full classpath is returned
     *   <li>Otherwise the method returns the classpath up to the generated bytecode associated with
     *       the key
     * </ul>
     *
     * @param key the key
     * @see #registerGeneratedBytecode(FileCollection)
     */
    @NonNull
    FileCollection getCompileClasspath(@Nullable Object key);

    /**
     * Returns the java compilation classpath as an ArtifactCollection
     *
     * <p>The provided key allows controlling how much of the classpath is returned.
     *
     * <ul>
     *   <li>if <code>null</code>, the full classpath is returned
     *   <li>Otherwise the method returns the classpath up to the generated bytecode associated with
     *       the key
     * </ul>
     *
     * @param key the key
     * @see #registerGeneratedBytecode(FileCollection)
     */
    @NonNull
    ArtifactCollection getCompileClasspathArtifacts(@Nullable Object key);
//
//    /**
//     * Returns the tasks for building external native projects.
//     *
//     * @deprecated Use {@link #getExternalNativeBuildProviders()}
//     */
//    @NonNull
//    @Deprecated
//    Collection<ExternalNativeBuildTask> getExternalNativeBuildTasks();
//
//    /**
//     * Returns the tasks for building external native projects.
//     *
//     * <p>Prefer this to {@link #getExternalNativeBuildTasks()} as it triggers eager configuration
//     * of the tasks.
//     */
//    @NonNull
//    Collection<TaskProvider<ExternalNativeBuildTask>> getExternalNativeBuildProviders();

    /**
     * Returns the obfuscation task. This can be null if obfuscation is not enabled.
     *
     * @deprecated This always returns null
     */
    @Nullable
    @Deprecated
    Task getObfuscation();

    /**
     * Returns the obfuscation mapping file. This can be null if obfuscation is not enabled.
     *
     * @deprecated Please use {@link #getMappingFileProvider()} instead in order to avoid task
     *     configuration.
     */
    @Nullable
    @Deprecated
    File getMappingFile();

    /**
     * Returns the provider of a file collection that contains an obfuscation mapping file. The file
     * collection may be empty if obfuscation is not enabled.
     */
    @NonNull
    Provider<FileCollection> getMappingFileProvider();

    /**
     * Returns the Java resource processing task.
     *
     * @deprecated Use {@link #getProcessJavaResourcesProvider()}
     */
    @NonNull
    @Deprecated
    AbstractCopyTask getProcessJavaResources();

    /**
     * Returns the Java resource processing task.
     *
     * <p>Prefer this to {@link #getProcessJavaResources()} as it triggers eager configuration of
     * the task.
     */
    @NonNull
    TaskProvider<AbstractCopyTask> getProcessJavaResourcesProvider();

    /**
     * Returns the assemble task for all this variant's output
     *
     * @deprecated Use {@link #getAssembleProvider()}
     */
    @Nullable
    @Deprecated
    Task getAssemble();

    /**
     * Returns the {@link TaskProvider} for the assemble task.
     *
     * <p>Prefer this to {@link #getAssemble()} as it triggers eager configuration of the task.
     */
    @Nullable
    TaskProvider<Task> getAssembleProvider();

    /**
     * Adds new Java source folders to the model.
     *
     * These source folders will not be used for the default build
     * system, but will be passed along the default Java source folders
     * to whoever queries the model.
     *
     * @param sourceFolders the source folders where the generated source code is.
     */
    void addJavaSourceFoldersToModel(@NonNull File... sourceFolders);

    /**
     * Adds new Java source folders to the model.
     *
     * These source folders will not be used for the default build
     * system, but will be passed along the default Java source folders
     * to whoever queries the model.
     *
     * @param sourceFolders the source folders where the generated source code is.
     */
    void addJavaSourceFoldersToModel(@NonNull Collection<File> sourceFolders);

    /**
     * Adds to the variant a task that generates Java source code.
     *
     * <p>This will make the generate[Variant]Sources task depend on this task and add the new
     * source folders as compilation inputs.
     *
     * <p>The new source folders are also added to the model.
     *
     * <p>N.B. This method also supports adding generated Kotlin sources, but this behavior may
     * change in the future. Future versions of AGP or Kotlin Gradle plugin may not support this.
     *
     * @param task the task
     * @param sourceFolders the source folders where the generated source code is.
     * @deprecated Use {@link #registerJavaGeneratingTask(TaskProvider, File...)}
     */
    @Deprecated
    void registerJavaGeneratingTask(@NonNull Task task, @NonNull File... sourceFolders);

    /**
     * Adds to the variant a task that generates Java source code.
     *
     * <p>This will make the generate[Variant]Sources task depend on this task and add the new
     * source folders as compilation inputs.
     *
     * <p>The new source folders are also added to the model.
     *
     * <p>N.B. This method also supports adding generated Kotlin sources, but this behavior may
     * change in the future. Future versions of AGP or Kotlin Gradle plugin may not support this.
     *
     * @param task the task
     * @param sourceFolders the source folders where the generated source code is.
     * @deprecated Use {@link #registerJavaGeneratingTask(TaskProvider, Collection<File>)}
     */
    @Deprecated
    void registerJavaGeneratingTask(@NonNull Task task, @NonNull Collection<File> sourceFolders);

    /**
     * Adds to the variant a task that generates Java source code.
     *
     * <p>This will make the generate[Variant]Sources task depend on this task and add the new
     * source folders as compilation inputs.
     *
     * <p>The new source folders are also added to the model.
     *
     * <p>N.B. This method also supports adding generated Kotlin sources, but this behavior may
     * change in the future. Future versions of AGP or Kotlin Gradle plugin may not support this.
     *
     * @param taskProvider the task provider
     * @param sourceFolders the source folders where the generated source code is.
     */
    void registerJavaGeneratingTask(
            @NonNull TaskProvider<? extends Task> taskProvider, @NonNull File... sourceFolders);

    /**
     * Adds to the variant a task that generates Java source code.
     *
     * <p>This will make the generate[Variant]Sources task depend on this task and add the new
     * source folders as compilation inputs.
     *
     * <p>The new source folders are also added to the model.
     *
     * <p>N.B. This method also supports adding generated Kotlin sources, but this behavior may
     * change in the future. Future versions of AGP or Kotlin Gradle plugin may not support this.
     *
     * @param taskProvider the task provider
     * @param sourceFolders the source folders where the generated source code is.
     */
    void registerJavaGeneratingTask(
            @NonNull TaskProvider<? extends Task> taskProvider,
            @NonNull Collection<File> sourceFolders);

    /**
     * Register the output of an external annotation processor.
     *
     * <p>The output is passed to the javac task, but the source generation hooks does not depend on
     * this.
     *
     * <p>In order to properly wire up tasks, the FileTree object must include dependency
     * information about the task that generates the content of this folders.
     *
     * @param folder a ConfigurableFileTree that contains a single folder and the task dependency
     *     information
     */
    void registerExternalAptJavaOutput(@NonNull ConfigurableFileTree folder);

    /**
     * Adds to the variant new generated resource folders.
     *
     * <p>In order to properly wire up tasks, the FileCollection object must include dependency
     * information about the task that generates the content of this folders.
     *
     * @param folders a FileCollection that contains the folders and the task dependency information
     */
    void registerGeneratedResFolders(@NonNull FileCollection folders);

    /**
     * Adds to the variant a task that generates Resources.
     *
     * This will make the generate[Variant]Resources task depend on this task and add the
     * new Resource folders as Resource merge inputs.
     *
     * The Resource folders are also added to the model.
     *
     * @param task the task
     * @param resFolders the folders where the generated resources are.
     *
     * @deprecated Use {@link #registerGeneratedResFolders(FileCollection)}
     */
    @Deprecated
    void registerResGeneratingTask(@NonNull Task task, @NonNull File... resFolders);

    /**
     * Adds to the variant a task that generates Resources.
     *
     * This will make the generate[Variant]Resources task depend on this task and add the
     * new Resource folders as Resource merge inputs.
     *
     * The Resource folders are also added to the model.
     *
     * @param task the task
     * @param resFolders the folders where the generated resources are.
     *
     * @deprecated Use {@link #registerGeneratedResFolders(FileCollection)}
     */
    @Deprecated
    void registerResGeneratingTask(@NonNull Task task, @NonNull Collection<File> resFolders);

    /**
     * Adds to the variant new generated Java byte-code.
     *
     * <p>This bytecode is passed to the javac classpath. This is typically used by compilers for
     * languages that generate bytecode ahead of javac.
     *
     * <p>The file collection can contain either a folder of class files or jars.
     *
     * <p>In order to properly wire up tasks, the FileCollection object must include dependency
     * information about the task that generates the content of these folders. This is generally
     * setup using {@link org.gradle.api.file.ConfigurableFileCollection#builtBy(Object...)}
     *
     * <p>The generated byte-code will also be added to the transform pipeline as a {@link
     * com.tyron.builder.api.transform.QualifiedContent.Scope#PROJECT} stream.
     *
     * <p>The method returns a key that can be used to query for the compilation classpath. This
     * allows each successive call to {@link #registerPreJavacGeneratedBytecode(FileCollection)} to
     * be associated with a classpath containing everything <strong>before</strong> the added
     * bytecode.
     *
     * @param fileCollection a FileCollection that contains the files and the task dependency
     *     information
     * @return a key for calls to {@link #registerGeneratedBytecode(FileCollection)}
     */
    Object registerPreJavacGeneratedBytecode(@NonNull FileCollection fileCollection);

    /** @deprecated use {@link #registerPreJavacGeneratedBytecode(FileCollection)} */
    @Deprecated
    Object registerGeneratedBytecode(@NonNull FileCollection fileCollection);

    /**
     * Adds to the variant new generated Java byte-code.
     *
     * <p>This bytecode is meant to be post javac, which means javac does not have it on its
     * classpath. It's is only added to the java compilation task's classpath and will be added to
     * the transform pipeline as a {@link
     * com.tyron.builder.api.transform.QualifiedContent.Scope#PROJECT} stream.
     *
     * <p>The file collection can contain either a folder of class files or jars.
     *
     * <p>In order to properly wire up tasks, the FileCollection object must include dependency
     * information about the task that generates the content of these folders. This is generally
     * setup using {@link org.gradle.api.file.ConfigurableFileCollection#builtBy(Object...)}
     *
     * @param fileCollection a FileCollection that contains the files and the task dependency
     *     information
     */
    void registerPostJavacGeneratedBytecode(@NonNull FileCollection fileCollection);

    /**
     * Adds a variant-specific BuildConfig field.
     *
     * @param type the type of the field
     * @param name the name of the field
     * @param value the value of the field
     */
    void buildConfigField(@NonNull String type, @NonNull String name, @NonNull String value);

    /**
     * Adds a variant-specific res value.
     * @param type the type of the field
     * @param name the name of the field
     * @param value the value of the field
     */
    void resValue(@NonNull String type, @NonNull String name, @NonNull String value);

    /**
     * Set up a new matching request for a given flavor dimension and value.
     *
     * <p>To learn more, read <a href="d.android.com/r/tools/use-flavorSelection.html">Select
     * default flavors for missing dimensions</a>.
     *
     * @param dimension the flavor dimension
     * @param requestedValue the flavor name
     */
    void missingDimensionStrategy(@NonNull String dimension, @NonNull String requestedValue);

    /**
     * Set up a new matching request for a given flavor dimension and value.
     *
     * <p>To learn more, read <a href="d.android.com/r/tools/use-flavorSelection.html">Select
     * default flavors for missing dimensions</a>.
     *
     * @param dimension the flavor dimension
     * @param requestedValues the flavor name and fallbacks
     */
    void missingDimensionStrategy(@NonNull String dimension, @NonNull String... requestedValues);

    /**
     * Set up a new matching request for a given flavor dimension and value.
     *
     * <p>To learn more, read <a href="d.android.com/r/tools/use-flavorSelection.html">Select
     * default flavors for missing dimensions</a>.
     *
     * @param dimension the flavor dimension
     * @param requestedValues the flavor name and fallbacks
     */
    void missingDimensionStrategy(@NonNull String dimension, @NonNull List<String> requestedValues);

    /**
     * If true, variant outputs will be considered signed. Only set if you manually set the outputs
     * to point to signed files built by other tasks.
     */
    void setOutputsAreSigned(boolean isSigned);

    /**
     * @see #setOutputsAreSigned(boolean)
     */
    boolean getOutputsAreSigned();

    /**
     * Returns file collection containing all raw Android resources, including the ones from
     * transitive dependencies.
     *
     * <p><strong>This is an incubating API, and it can be changed or removed without
     * notice.</strong>
     */
    @Incubating
    @NonNull
    FileCollection getAllRawAndroidResources();

    /**
     * Registers a task to be executed before any main output tasks like the assemble or bundle
     * tasks are invoked.
     *
     * <p>The task will need to set up its dependencies on the build outputs (whether it is an
     * intermediate output or the final one) independently of this call.
     */
    @Incubating
    void register(Task task);
}
