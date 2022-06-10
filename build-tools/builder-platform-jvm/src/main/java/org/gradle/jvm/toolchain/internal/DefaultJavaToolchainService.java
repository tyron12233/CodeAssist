package org.gradle.jvm.toolchain.internal;

import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.jvm.toolchain.JavaCompiler;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.toolchain.JavaToolchainSpec;
import org.gradle.jvm.toolchain.JavadocTool;

import javax.inject.Inject;

public class DefaultJavaToolchainService implements JavaToolchainService {

    private final JavaToolchainQueryService queryService;
    private final ObjectFactory objectFactory;

    @Inject
    public DefaultJavaToolchainService(JavaToolchainQueryService queryService, ObjectFactory objectFactory) {
        this.queryService = queryService;
        this.objectFactory = objectFactory;
    }

    @Override
    public Provider<JavaCompiler> compilerFor(Action<? super JavaToolchainSpec> config) {
        return compilerFor(configureToolchainSpec(config));
    }

    @Override
    public Provider<JavaCompiler> compilerFor(JavaToolchainSpec spec) {
        return queryService.toolFor(spec, JavaToolchain::getJavaCompiler);
    }

    @Override
    public Provider<JavaLauncher> launcherFor(Action<? super JavaToolchainSpec> config) {
        return launcherFor(configureToolchainSpec(config));
    }

    @Override
    public Provider<JavaLauncher> launcherFor(JavaToolchainSpec spec) {
        return queryService.toolFor(spec, JavaToolchain::getJavaLauncher);
    }

    @Override
    public Provider<JavadocTool> javadocToolFor(Action<? super JavaToolchainSpec> config) {
        return javadocToolFor(configureToolchainSpec(config));
    }

    @Override
    public Provider<JavadocTool> javadocToolFor(JavaToolchainSpec spec) {
        return queryService.toolFor(spec, JavaToolchain::getJavadocTool);
    }

    private DefaultToolchainSpec configureToolchainSpec(Action<? super JavaToolchainSpec> config) {
        DefaultToolchainSpec toolchainSpec = objectFactory.newInstance(DefaultToolchainSpec.class);
        config.execute(toolchainSpec);
        return toolchainSpec;
    }
}
