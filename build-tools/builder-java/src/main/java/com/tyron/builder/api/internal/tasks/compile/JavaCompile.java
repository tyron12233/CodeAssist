package com.tyron.builder.api.internal.tasks.compile;

import static com.google.common.base.Preconditions.checkState;

import com.sun.tools.javac.main.JavaCompiler;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.internal.project.taskfactory.IncrementalTaskAction;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.providers.Property;
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
import com.tyron.builder.api.tasks.compile.AbstractCompile;
import com.tyron.builder.api.tasks.incremental.IncrementalTaskInputs;
import com.tyron.builder.api.work.Incremental;
import com.tyron.builder.api.work.InputChanges;
import com.tyron.builder.api.work.NormalizeLineEndings;

import java.io.File;
import java.util.concurrent.Callable;

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
public class JavaCompile extends AbstractCompile {

    private final CompileOptions compileOptions;
    private final FileCollection stableSources = getProject().files((Callable<FileTree>) this::getSource);
    private File previousCompilationDataFile;
    private final Property<JavaCompiler> javaCompiler;
    private final ObjectFactory objectFactory;

    public JavaCompile() {
        objectFactory = getProject().getObjects();
        compileOptions = objectFactory.newInstance(CompileOptions.class);
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
     * @see org.gradle.jvm.toolchain.JavaToolchainSpec
     * @since 6.7
     */
    @Nested
    @Optional
    public Property<JavaCompiler> getJavaCompiler() {
        return javaCompiler;
    }

    protected void compile(InputChanges inputs) {
//        DefaultJavaCompileSpec spec = createSpec();
//        if (!compileOptions.isIncremental()) {
//            performFullCompilation(spec);
//        } else {
//            performIncrementalCompilation(inputs, spec);
//        }
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
}
