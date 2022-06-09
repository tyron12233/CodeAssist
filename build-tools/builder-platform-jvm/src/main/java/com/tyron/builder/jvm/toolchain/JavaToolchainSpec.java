package com.tyron.builder.jvm.toolchain;

import com.tyron.builder.api.Describable;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.internal.HasInternalProtocol;

/**
 * Requirements for selecting a Java toolchain.
 * <p>
 * A toolchain is a JRE/JDK used by the tasks of a build. Tasks of a build may require one or more of the tools javac, java, or javadoc) of a toolchain.
 * Depending on the needs of a build, only toolchains matching specific characteristics can be used to run a build or a specific task of a build.
 *
 * @since 6.7
 */
@HasInternalProtocol
public interface JavaToolchainSpec extends Describable {

    /**
     * The exact version of the Java language that the toolchain is required to support.
     */
    Property<JavaLanguageVersion> getLanguageVersion();

    /**
     * The vendor of the toolchain.
     * <p>By default, toolchains from any vendor are eligible.</p>
     *
     * @since 6.8
     */
    Property<JvmVendorSpec> getVendor();

    /**
     * The virtual machine implementation of the toolchain.
     * <p>By default, any implementation (hotspot, j9, ...) is eligible.</p>
     *
     * @since 6.8
     */
    Property<JvmImplementation> getImplementation();

}
