package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.internal.provider.DefaultProvider;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.provider.ProviderFactory;
import com.tyron.builder.internal.jvm.Jvm;
import com.tyron.builder.jvm.toolchain.JavaToolchainSpec;
import com.tyron.builder.jvm.toolchain.install.internal.DefaultJavaToolchainProvisioningService;
import com.tyron.builder.jvm.toolchain.install.internal.JavaToolchainProvisioningService;

import javax.inject.Inject;
import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class JavaToolchainQueryService {

    private final JavaInstallationRegistry registry;
    private final JavaToolchainFactory toolchainFactory;
    private final JavaToolchainProvisioningService installService;
    private final Provider<Boolean> detectEnabled;
    private final Provider<Boolean> downloadEnabled;
    private final Map<JavaToolchainSpec, JavaToolchain> matchingToolchains;

    @Inject
    public JavaToolchainQueryService(JavaInstallationRegistry registry, JavaToolchainFactory toolchainFactory, JavaToolchainProvisioningService provisioningService, ProviderFactory factory) {
        this.registry = registry;
        this.toolchainFactory = toolchainFactory;
        this.installService = provisioningService;
        this.detectEnabled = factory.gradleProperty(AutoDetectingInstallationSupplier.AUTO_DETECT).map(Boolean::parseBoolean);
        this.downloadEnabled = factory.gradleProperty(DefaultJavaToolchainProvisioningService.AUTO_DOWNLOAD).map(Boolean::parseBoolean);
        this.matchingToolchains = new ConcurrentHashMap<>();
    }

    <T> Provider<T> toolFor(JavaToolchainSpec spec, Transformer<T, JavaToolchain> toolFunction) {
        return findMatchingToolchain(spec).map(toolFunction);
    }

    Provider<JavaToolchain> findMatchingToolchain(JavaToolchainSpec filter) {
        return new DefaultProvider<>(() -> {
            if (((ToolchainSpecInternal) filter).isConfigured()) {
                return matchingToolchains.computeIfAbsent(filter, k -> query(k));
            } else {
                return null;
            }
        });
    }

    private JavaToolchain query(JavaToolchainSpec filter) {
        if (filter instanceof CurrentJvmToolchainSpec) {
            return asToolchain(Jvm.current().getJavaHome(), filter).get();
        }
        if (filter instanceof SpecificInstallationToolchainSpec) {
            return asToolchain(((SpecificInstallationToolchainSpec) filter).getJavaHome(), filter).get();
        }

        return registry.listInstallations().stream()
            .map(InstallationLocation::getLocation)
            .map(javaHome -> asToolchain(javaHome, filter))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .filter(new ToolchainMatcher(filter))
            .min(new JavaToolchainComparator())
            .orElseGet(() -> downloadToolchain(filter));
    }

    private JavaToolchain downloadToolchain(JavaToolchainSpec spec) {
        final Optional<File> installation = installService.tryInstall(spec);
        final Optional<JavaToolchain> toolchain = installation
            .map(home -> asToolchain(home, spec))
            .orElseThrow(noToolchainAvailable(spec));
        return toolchain.orElseThrow(provisionedToolchainIsInvalid(installation::get));
    }

    private Supplier<BuildException> noToolchainAvailable(JavaToolchainSpec spec) {
        return () -> new NoToolchainAvailableException(spec, detectEnabled.getOrElse(true), downloadEnabled.getOrElse(true));
    }

    private Supplier<BuildException> provisionedToolchainIsInvalid(Supplier<File> javaHome) {
        return () -> new BuildException("Provisioned toolchain '" + javaHome.get() + "' could not be probed.");
    }

    private Optional<JavaToolchain> asToolchain(File javaHome, JavaToolchainSpec spec) {
        return toolchainFactory.newInstance(javaHome, new JavaToolchainInput(spec));
    }
}
