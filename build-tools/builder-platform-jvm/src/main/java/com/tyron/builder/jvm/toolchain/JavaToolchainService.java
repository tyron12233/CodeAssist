package com.tyron.builder.jvm.toolchain;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.provider.Provider;

/**
 * Allows to query for toolchain managed tools, like {@link JavaCompiler}, {@link JavaLauncher} and {@link JavadocTool}.
 * <p>
 * An instance of this service is available for injection into tasks, plugins and other types.
 *
 * @since 6.7
 */
public interface JavaToolchainService {

    /**
     * Obtain a {@link JavaCompiler} matching the {@link JavaToolchainSpec}, as configured by the provided action.
     *
     * @param config The configuration of the {@code JavaToolchainSpec}
     * @return A {@code Provider<JavaCompiler>}
     */
    Provider<JavaCompiler> compilerFor(Action<? super JavaToolchainSpec> config);

    /**
     * Obtain a {@link JavaCompiler} matching the {@link JavaToolchainSpec}.
     *
     * @param spec The {@code JavaToolchainSpec}
     * @return A {@code Provider<JavaCompiler>}
     */
    Provider<JavaCompiler> compilerFor(JavaToolchainSpec spec);

    /**
     * Obtain a {@link JavaLauncher} matching the {@link JavaToolchainSpec}, as configured by the provided action.
     *
     * @param config The configuration of the {@code JavaToolchainSpec}
     * @return A {@code Provider<JavaLauncher>}
     */
    Provider<JavaLauncher> launcherFor(Action<? super JavaToolchainSpec> config);

    /**
     * Obtain a {@link JavaLauncher} matching the {@link JavaToolchainSpec}.
     *
     * @param spec The {@code JavaToolchainSpec}
     * @return A {@code Provider<JavaLauncher>}
     */
    Provider<JavaLauncher> launcherFor(JavaToolchainSpec spec);

    /**
     * Obtain a {@link JavadocTool} matching the {@link JavaToolchainSpec}, as configured by the provided action.
     *
     * @param config The configuration of the {@code JavaToolchainSpec}
     * @return A {@code Provider<JavadocTool>}
     */
    Provider<JavadocTool> javadocToolFor(Action<? super JavaToolchainSpec> config);

    /**
     * Obtain a {@link JavadocTool} matching the {@link JavaToolchainSpec}.
     *
     * @param spec The {@code JavaToolchainSpec}
     * @return A {@code Provider<JavadocTool>}
     */
    Provider<JavadocTool> javadocToolFor(JavaToolchainSpec spec);
}
