package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.internal.jvm.inspection.JvmVendor;

import java.io.File;
import java.util.Comparator;

public class JavaToolchainComparator implements Comparator<JavaToolchain> {

    @Override
    public int compare(JavaToolchain o1, JavaToolchain o2) {
        return Comparator
            .comparing(JavaToolchain::isCurrentJvm)
            .thenComparing(JavaToolchain::isJdk)
            .thenComparing(this::extractVendor, Comparator.reverseOrder())
            .thenComparing(JavaToolchain::getToolVersion)
            // It is possible for different JDK builds to have exact same version. The input order
            // may change so the installation path breaks ties to keep sorted output consistent
            // between runs.
            .thenComparing(this::extractInstallationPathAsFile)
            .reversed()
            .compare(o1, o2);
    }

    private JvmVendor.KnownJvmVendor extractVendor(JavaToolchain toolchain) {
        return toolchain.getMetadata().getVendor().getKnownVendor();
    }

    private File extractInstallationPathAsFile(JavaToolchain javaToolchain) {
        return javaToolchain.getInstallationPath().getAsFile();
    }
}
