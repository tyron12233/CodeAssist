package com.tyron.builder.jvm.toolchain.internal;

import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;

public class FileBasedInstallationFactory {

    public static Set<InstallationLocation> fromDirectory(File rootDirectory, String supplierName) {
        final File[] javaCandidates = rootDirectory.listFiles();
        if (javaCandidates == null) {
            return Collections.emptySet();
        }
        return Stream.of(javaCandidates)
            .filter(File::isDirectory)
            .map(d -> new InstallationLocation(d, supplierName))
            .collect(toSet());
    }

}
