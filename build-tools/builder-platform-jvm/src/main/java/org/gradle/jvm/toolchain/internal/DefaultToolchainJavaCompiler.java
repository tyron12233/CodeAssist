package org.gradle.jvm.toolchain.internal;

import org.gradle.api.file.RegularFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.WorkResult;
import org.gradle.jvm.toolchain.JavaCompiler;
import org.gradle.jvm.toolchain.JavaInstallationMetadata;
import org.gradle.language.base.internal.compile.CompileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultToolchainJavaCompiler implements JavaCompiler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultToolchainJavaCompiler.class);

    private final JavaToolchain javaToolchain;
    private final JavaCompilerFactory compilerFactory;

    public DefaultToolchainJavaCompiler(JavaToolchain javaToolchain, JavaCompilerFactory compilerFactory) {
        this.javaToolchain = javaToolchain;
        this.compilerFactory = compilerFactory;
    }

    @Override
    @Nested
    public JavaInstallationMetadata getMetadata() {
        return javaToolchain;
    }

    @Override
    @Internal
    public RegularFile getExecutablePath() {
        return javaToolchain.findExecutable("javac");
    }

    @SuppressWarnings("unchecked")
    public <T extends CompileSpec> WorkResult execute(T spec) {
        LOGGER.info("Compiling with toolchain '{}'.", javaToolchain.getDisplayName());
        final Class<T> specType = (Class<T>) spec.getClass();
        return compilerFactory.create(specType).execute(spec);
    }

}
