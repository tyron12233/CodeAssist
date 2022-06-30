package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.api.file.RegularFile;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.Nested;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.jvm.toolchain.JavaCompiler;
import com.tyron.builder.jvm.toolchain.JavaInstallationMetadata;
import com.tyron.builder.language.base.internal.compile.CompileSpec;
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
