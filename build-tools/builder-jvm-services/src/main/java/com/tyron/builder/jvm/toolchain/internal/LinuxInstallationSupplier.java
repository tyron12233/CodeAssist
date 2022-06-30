package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.api.provider.ProviderFactory;
import com.tyron.builder.internal.os.OperatingSystem;

import javax.inject.Inject;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

public class LinuxInstallationSupplier extends AutoDetectingInstallationSupplier {

    private final String[] roots;
    private final OperatingSystem os;

    @Inject
    public LinuxInstallationSupplier(ProviderFactory factory) {
        this(factory, OperatingSystem.current(), "/usr/lib/jvm", "/usr/lib64/jvm", "/usr/java");
    }

    private LinuxInstallationSupplier(ProviderFactory factory, OperatingSystem os, String... roots) {
        super(factory);
        this.roots = roots;
        this.os = os;
    }

    @Override
    protected Set<InstallationLocation> findCandidates() {
        if (os.isLinux()) {
            return Arrays.stream(roots)
                .map(root -> FileBasedInstallationFactory.fromDirectory(new File(root), "Common Linux Locations"))
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
        }
        return Collections.emptySet();
    }

}
