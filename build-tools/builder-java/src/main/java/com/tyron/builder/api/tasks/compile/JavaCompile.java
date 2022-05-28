package com.tyron.builder.api.tasks.compile;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.main.JavaCompiler;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.file.ProjectLayout;
import com.tyron.builder.api.internal.tasks.compile.CleaningJavaCompiler;
import com.tyron.builder.api.internal.tasks.compile.CommandLineJavaCompileSpec;
import com.tyron.builder.api.internal.tasks.compile.CompilationSourceDirs;
import com.tyron.builder.api.internal.tasks.compile.CompileJavaBuildOperationReportingCompiler;
import com.tyron.builder.api.internal.tasks.compile.CompileOptions;
import com.tyron.builder.api.internal.tasks.compile.DefaultJavaCompileSpec;
import com.tyron.builder.api.internal.tasks.compile.DefaultJavaCompileSpecFactory;
import com.tyron.builder.api.internal.tasks.compile.JavaCompileSpec;
import com.tyron.builder.api.internal.tasks.compile.JdkJavaCompiler;
import com.tyron.builder.api.tasks.compile.ForkOptions;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.project.taskfactory.IncrementalTaskAction;
import com.tyron.builder.api.internal.tasks.compile.incremental.IncrementalCompilerFactory;
import com.tyron.builder.api.internal.tasks.compile.incremental.recomp.JavaRecompilationSpecProvider;
import com.tyron.builder.api.internal.tasks.compile.javac.DefaultIncrementalCompilationAwareJavaCompiler;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.tasks.CacheableTask;
import com.tyron.builder.api.tasks.CompileClasspath;
import com.tyron.builder.api.tasks.IgnoreEmptyDirectories;
import com.tyron.builder.api.tasks.InputFiles;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.Nested;
import com.tyron.builder.api.tasks.Optional;
import com.tyron.builder.api.tasks.OutputFile;
import com.tyron.builder.api.tasks.PathSensitive;
import com.tyron.builder.api.tasks.PathSensitivity;
import com.tyron.builder.api.tasks.SkipWhenEmpty;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.tasks.compile.AbstractCompile;
import com.tyron.builder.work.Incremental;
import com.tyron.builder.work.InputChanges;
import com.tyron.builder.work.NormalizeLineEndings;
import com.tyron.builder.jvm.toolchain.JavaInstallationMetadata;
import com.tyron.builder.jvm.toolchain.JavaLanguageVersion;
import com.tyron.builder.language.base.internal.compile.Compiler;

import org.apache.tools.ant.taskdefs.Javac;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

import javax.tools.ToolProvider;

/**
 * Compiles Java source files.
 *
 * <pre class='autoTested'>
 *     plugins {
 *         id 'java'
 *     }
 *
 *     tasks.withType(JavaCompile) {
 *         //enable compilation in a separate daemon process
 *         options.fork = true
 *     }
 * </pre>
 */
@CacheableTask
public class  JavaCompile extends AbstractCompile {

    private final com.tyron.builder.api.internal.tasks.compile.CompileOptions compileOptions;
    private final FileCollection stableSources = getProject().files((Callable<FileTree>) this::getSource);
    private File previousCompilationDataFile;
    private final Property<JavaCompiler> javaCompiler;
    private final ObjectFactory objectFactory;

    public JavaCompile() {
        objectFactory = getProject().getObjects();
        compileOptions = objectFactory.newInstance(
                com.tyron.builder.api.internal.tasks.compile.CompileOptions.class, objectFactory);
        javaCompiler = objectFactory.property(JavaCompiler.class);
        javaCompiler.finalizeValueOnRead();
//        CompilerForkUtils.doNotCacheIfForkingViaExecutable(compileOptions, getOutputs());

        doLast(new IncrementalTaskAction() {
            @Override
            public void execute(InputChanges inputs) {
                compile(inputs);
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Internal("tracked via stableSources")
    public FileTree getSource() {
        return super.getSource();
    }

    /**
     * Configures the java compiler to be used to compile the Java source.
     *
     * @see JavaToolchainSpec
     * @since 6.7
     */
    @Nested
    @Optional
    public Property<JavaCompiler> getJavaCompiler() {
        return javaCompiler;
    }

    protected void compile(InputChanges inputs) {
        DefaultJavaCompileSpec spec = createSpec();
        if (!compileOptions.isIncremental()) {
            performFullCompilation(spec);
        } else {
            performIncrementalCompilation(inputs, spec);
        }
    }

    private void performIncrementalCompilation(InputChanges inputs, DefaultJavaCompileSpec spec) {
        boolean isUsingCliCompiler = isUsingCliCompiler(spec);
        spec.getCompileOptions().setSupportsCompilerApi(!isUsingCliCompiler);
        spec.getCompileOptions().setSupportsConstantAnalysis(!isUsingCliCompiler);
        spec.getCompileOptions().setPreviousCompilationDataFile(getPreviousCompilationData());

        Compiler<JavaCompileSpec> compiler = createCompiler();
        compiler = makeIncremental(inputs, (CleaningJavaCompiler<JavaCompileSpec>) compiler, getStableSources());
        performCompilation(spec, compiler);
    }

    private Compiler<JavaCompileSpec> makeIncremental(InputChanges inputs, CleaningJavaCompiler<JavaCompileSpec> compiler, FileCollection stableSources) {
        FileTree sources = stableSources.getAsFileTree();
        return getIncrementalCompilerFactory().makeIncremental(
                compiler,
                sources,
                createRecompilationSpec(inputs, sources)
        );
    }

    private JavaRecompilationSpecProvider createRecompilationSpec(InputChanges inputs, FileTree sources) {
        return new JavaRecompilationSpecProvider(
                getDeleter(),
                getServices().get(FileOperations.class),
                sources,
                inputs.isIncremental(),
                () -> inputs.getFileChanges(getStableSources()).iterator()
        );
    }

    private Deleter getDeleter() {
        return getServices().get(Deleter.class);
    }

    private IncrementalCompilerFactory getIncrementalCompilerFactory() {
        return getServices().get(IncrementalCompilerFactory.class);
    }

    private boolean isUsingCliCompiler(DefaultJavaCompileSpec spec) {
        return CommandLineJavaCompileSpec.class.isAssignableFrom(spec.getClass());
    }

    private void performFullCompilation(DefaultJavaCompileSpec spec) {
        Compiler<JavaCompileSpec> compiler;
        spec.setSourceFiles(getStableSources());
        compiler = createCompiler();
        performCompilation(spec, compiler);
    }

    private WorkResult performCompilation(JavaCompileSpec spec, Compiler<JavaCompileSpec> compiler) {
        WorkResult result = new CompileJavaBuildOperationReportingCompiler(this, compiler, getServices().get(BuildOperationExecutor.class)).execute(spec);
        setDidWork(result.getDidWork());
        return result;
    }

     private Compiler<JavaCompileSpec> createCompiler() {
         Compiler<JavaCompileSpec> javaCompiler = createToolchainCompiler();
         return new CleaningJavaCompiler<>(javaCompiler, getOutputs(), getDeleter());
     }

    private Compiler<JavaCompileSpec> createToolchainCompiler() {
        return spec -> {
            JdkJavaCompiler javaCompiler = new JdkJavaCompiler(
                    () -> new DefaultIncrementalCompilationAwareJavaCompiler(ToolProvider.getSystemJavaCompiler()));
            return javaCompiler.execute(spec);
        };
    }

    private Provider<JavaCompiler> getCompilerTool() {
        return null;
    }


    private void validateConfiguration() {
        if (javaCompiler.isPresent()) {
            checkState(getOptions().getForkOptions().getJavaHome() == null, "Must not use `javaHome` property on `ForkOptions` together with `javaCompiler` property");
            checkState(getOptions().getForkOptions().getExecutable() == null, "Must not use `executable` property on `ForkOptions` together with `javaCompiler` property");
        }
    }

    /**
     * The previous compilation analysis. Internal use only.
     *
     * @since 7.1
     */
    @OutputFile
    protected File getPreviousCompilationData() {
        if (previousCompilationDataFile == null) {
            previousCompilationDataFile = new File(getTemporaryDirWithoutCreating(), "previous-compilation-data.bin");
        }
        return previousCompilationDataFile;
    }

    private File getTemporaryDirWithoutCreating() {
        // Do not create the temporary folder, since that causes problems.
        return getServices().get(TemporaryFileProvider.class).newTemporaryFile(getName());
    }

    /**
     * Returns the compilation options.
     *
     * @return The compilation options.
     */
    @Nested
    public CompileOptions getOptions() {
        return compileOptions;
    }

    @Override
    @CompileClasspath
    @Incremental
    public FileCollection getClasspath() {
        return super.getClasspath();
    }

    /**
     * The sources for incremental change detection.
     *
     * @since 6.0
     */
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @NormalizeLineEndings
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    protected FileCollection getStableSources() {
        return stableSources;
    }

    DefaultJavaCompileSpec createSpec() {
        validateConfiguration();
        List<File> sourcesRoots = CompilationSourceDirs.inferSourceRoots((FileTreeInternal) getStableSources().getAsFileTree());
//        JavaModuleDetector javaModuleDetector = getJavaModuleDetector();
        boolean isModule = false; //JavaModuleDetector.isModuleSource(modularity.getInferModulePath().get(), sourcesRoots);
        boolean toolchainCompatibleWithJava8 = true;//isToolchainCompatibleWithJava8();
        boolean isSourcepathUserDefined = compileOptions.getSourcepath() != null && !compileOptions.getSourcepath().isEmpty();

        final DefaultJavaCompileSpec spec = createBaseSpec();

        spec.setDestinationDir(getDestinationDirectory().getAsFile().get());
        spec.setWorkingDir(getProjectLayout().getProjectDirectory().getAsFile());
        spec.setTempDir(getTemporaryDir());
        spec.setCompileClasspath(ImmutableList.copyOf(getClasspath()));
//        spec.setCompileClasspath(
//                ImmutableList.copyOf(javaModuleDetector.inferClasspath(isModule, getClasspath())));
//        spec.setModulePath(ImmutableList.copyOf(javaModuleDetector.inferModulePath(isModule, getClasspath())));
        if (isModule && !isSourcepathUserDefined) {
            compileOptions.setSourcepath(getProjectLayout().files(sourcesRoots));
        }
        spec.setAnnotationProcessorPath(compileOptions.getAnnotationProcessorPath() == null ? ImmutableList.of() : ImmutableList.copyOf(compileOptions.getAnnotationProcessorPath()));
        configureCompatibilityOptions(spec);
        spec.setSourcesRoots(sourcesRoots);

        if (!toolchainCompatibleWithJava8) {
            spec.getCompileOptions().setHeaderOutputDirectory(null);
        }

        // TODO: add AnnotationProcessorDiscoveringCompiler
        //  adding empty path for now
        spec.setEffectiveAnnotationProcessors(ImmutableSet.of());

        return spec;
    }

    private ProjectLayout getProjectLayout() {
        return getServices().get(ProjectLayout.class);
    }

    private DefaultJavaCompileSpec createBaseSpec() {
        final ForkOptions forkOptions = compileOptions.getForkOptions();
        if (javaCompiler.isPresent()) {
            applyToolchain(forkOptions);
        }
        return new DefaultJavaCompileSpecFactory(compileOptions, getToolchain()).create();
    }

    private void applyToolchain(ForkOptions forkOptions) {
//        final JavaInstallationMetadata metadata = getToolchain();
//        forkOptions.setJavaHome(metadata.getInstallationPath().getAsFile());

    }

    private void configureCompatibilityOptions(DefaultJavaCompileSpec spec) {
        final JavaInstallationMetadata toolchain = getToolchain();
        if (toolchain != null) {
            if (compileOptions.getRelease().isPresent()) {
                spec.setRelease(compileOptions.getRelease().get());
            } else {
                boolean isSourceOrTargetConfigured = false;
                if (super.getSourceCompatibility() != null) {
                    spec.setSourceCompatibility(getSourceCompatibility());
                    isSourceOrTargetConfigured = true;
                }
                if (super.getTargetCompatibility() != null) {
                    spec.setTargetCompatibility(getTargetCompatibility());
                    isSourceOrTargetConfigured = true;
                }
                if (!isSourceOrTargetConfigured) {
                    JavaLanguageVersion languageVersion = toolchain.getLanguageVersion();
                    if (languageVersion.canCompileOrRun(10)) {
                        spec.setRelease(languageVersion.asInt());
                    } else {
                        String version = languageVersion.toString();
                        spec.setSourceCompatibility(version);
                        spec.setTargetCompatibility(version);
                    }
                }
            }
        } else if (compileOptions.getRelease().isPresent()) {
            spec.setRelease(compileOptions.getRelease().get());
        } else {
            spec.setTargetCompatibility(getTargetCompatibility());
            spec.setSourceCompatibility(getSourceCompatibility());
        }
        spec.setCompileOptions(compileOptions);
    }

    private JavaInstallationMetadata getToolchain() {
        return null;
    }
}
