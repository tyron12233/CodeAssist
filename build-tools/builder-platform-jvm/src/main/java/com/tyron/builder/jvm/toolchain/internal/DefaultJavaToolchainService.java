package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.jvm.toolchain.JavaCompiler;
import com.tyron.builder.jvm.toolchain.JavaLauncher;
import com.tyron.builder.jvm.toolchain.JavaToolchainService;
import com.tyron.builder.jvm.toolchain.JavaToolchainSpec;
import com.tyron.builder.jvm.toolchain.JavadocTool;

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
