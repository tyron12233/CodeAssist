package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.api.Describable;
import com.tyron.builder.api.file.Directory;
import com.tyron.builder.api.file.RegularFile;
import com.tyron.builder.api.internal.file.FileFactory;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.Nested;
import com.tyron.builder.internal.jvm.Jvm;
import com.tyron.builder.internal.jvm.inspection.JvmInstallationMetadata;
import com.tyron.builder.internal.os.OperatingSystem;
import com.tyron.builder.jvm.toolchain.JavaCompiler;
import com.tyron.builder.jvm.toolchain.JavaInstallationMetadata;
import com.tyron.builder.jvm.toolchain.JavaLanguageVersion;
import com.tyron.builder.jvm.toolchain.JavaLauncher;
import com.tyron.builder.jvm.toolchain.JavadocTool;
import com.tyron.builder.util.internal.VersionNumber;

import java.nio.file.Path;

public class JavaToolchain implements Describable, JavaInstallationMetadata {

    private final JavaCompilerFactory compilerFactory;
    private final ToolchainToolFactory toolFactory;
    private final Directory javaHome;
    private final VersionNumber implementationVersion;
    private final JavaLanguageVersion javaVersion;
    private final JvmInstallationMetadata metadata;
    private final JavaToolchainInput input;

    public JavaToolchain(JvmInstallationMetadata metadata, JavaCompilerFactory compilerFactory, ToolchainToolFactory toolFactory, FileFactory fileFactory, JavaToolchainInput input) {
        this.javaHome = fileFactory.dir(computeEnclosingJavaHome(metadata.getJavaHome()).toFile());
        this.javaVersion = JavaLanguageVersion.of(metadata.getLanguageVersion().getMajorVersion());
        this.compilerFactory = compilerFactory;
        this.toolFactory = toolFactory;
        this.implementationVersion = VersionNumber.withPatchNumber().parse(metadata.getImplementationVersion());
        this.metadata = metadata;
        this.input = input;
    }

    @Nested
    protected JavaToolchainInput getTaskInputs() {
        return input;
    }

    @Internal
    public JavaCompiler getJavaCompiler() {
        return new DefaultToolchainJavaCompiler(this, compilerFactory);
    }

    @Internal
    public JavaLauncher getJavaLauncher() {
        return new DefaultToolchainJavaLauncher(this);
    }

    @Internal
    public JavadocTool getJavadocTool() {
        return toolFactory.create(JavadocTool.class, this);
    }

    @Internal
    public JavaLanguageVersion getLanguageVersion() {
        return javaVersion;
    }

    @Internal
    @Override
    public String getJavaRuntimeVersion() {
        return metadata.getRuntimeVersion();
    }

    @Override
    public String getJvmVersion() {
        return metadata.getJvmVersion();
    }

    @Internal
    public VersionNumber getToolVersion() {
        return implementationVersion;
    }

    @Internal
    public Directory getInstallationPath() {
        return javaHome;
    }

    @Internal
    public boolean isJdk() {
        return metadata.hasCapability(JvmInstallationMetadata.JavaInstallationCapability.JAVA_COMPILER);
    }

    @Internal
    public boolean isCurrentJvm() {
        return javaHome.getAsFile().equals(Jvm.current().getJavaHome());
    }

    @Internal
    public JvmInstallationMetadata getMetadata() {
        return metadata;
    }

    @Override
    public String getVendor() {
        return metadata.getVendor().getDisplayName();
    }

    @Internal
    @Override
    public String getDisplayName() {
        return javaHome.toString();
    }

    public RegularFile findExecutable(String toolname) {
        return getInstallationPath().file(getBinaryPath(toolname));
    }

    private Path computeEnclosingJavaHome(Path home) {
        final Path parentPath = home.getParent();
        final boolean isEmbeddedJre = home.getFileName().toString().equalsIgnoreCase("jre");
        if (isEmbeddedJre && parentPath.resolve(getBinaryPath("java")).toFile().exists()) {
            return parentPath;
        }
        return home;
    }

    private String getBinaryPath(String java) {
        return "bin/" + OperatingSystem.current().getExecutableName(java);
    }
}
