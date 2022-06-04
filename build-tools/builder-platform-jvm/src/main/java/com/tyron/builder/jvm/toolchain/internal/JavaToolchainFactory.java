package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.api.internal.file.FileFactory;
import com.tyron.builder.internal.jvm.inspection.JvmInstallationMetadata;
import com.tyron.builder.internal.jvm.inspection.JvmMetadataDetector;

import javax.inject.Inject;
import java.io.File;
import java.util.Optional;

public class JavaToolchainFactory {

    private final JavaCompilerFactory compilerFactory;
    private final ToolchainToolFactory toolFactory;
    private final FileFactory fileFactory;
    private final JvmMetadataDetector detector;

    @Inject
    public JavaToolchainFactory(JvmMetadataDetector detector, JavaCompilerFactory compilerFactory, ToolchainToolFactory toolFactory, FileFactory fileFactory) {
        this.detector = detector;
        this.compilerFactory = compilerFactory;
        this.toolFactory = toolFactory;
        this.fileFactory = fileFactory;
    }

    public Optional<JavaToolchain> newInstance(File javaHome, JavaToolchainInput input) {
        final JvmInstallationMetadata metadata = detector.getMetadata(javaHome);
        if(metadata.isValidInstallation()) {
            final JavaToolchain toolchain = new JavaToolchain(metadata, compilerFactory, toolFactory, fileFactory, input);
            return Optional.of(toolchain);
        }
        return Optional.empty();
    }

}
