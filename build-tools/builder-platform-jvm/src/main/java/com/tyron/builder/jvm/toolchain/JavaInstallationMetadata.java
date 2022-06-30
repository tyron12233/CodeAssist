package com.tyron.builder.jvm.toolchain;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.file.Directory;
import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.Internal;

/**
 * Metadata about a Java tool obtained from a toolchain.
 *
 * @see JavaLauncher
 * @see JavaCompiler
 * @see JavadocTool
 *
 * @since 6.7
 */
public interface JavaInstallationMetadata {
    /**
     * Returns the language version of the JVM to which this tool belongs
     *
     * @return the {@code JavaLanguageVersion}
     */
    @Input
    JavaLanguageVersion getLanguageVersion();

    /**
     * Returns the full Java version (including the build number) of the JVM, as specified in its {@code java.runtime.version} property.
     *
     * @return the full Java version of the JVM
     * @since 7.1
     */
    @Internal
    @Incubating
    String getJavaRuntimeVersion();

    /**
     * Returns the version of the JVM, as specified in its {@code java.vm.version} property.
     *
     * @return the version of the JVM
     * @since 7.1
     */
    @Internal
    @Incubating
    String getJvmVersion();

    /**
     * Returns a human-readable string for the vendor of the JVM.
     *
     * @return the vendor
     * @since 6.8
     */
    @Internal
    String getVendor();

    /**
     * The path to installation this tool belongs to.
     * <p>
     * This value matches what would be the content of {@code JAVA_HOME} for the given installation.
     *
     * @return the installation path
     */
    @Internal
    Directory getInstallationPath();
}
