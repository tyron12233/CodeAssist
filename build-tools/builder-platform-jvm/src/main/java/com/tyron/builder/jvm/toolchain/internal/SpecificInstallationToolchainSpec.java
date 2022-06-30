package com.tyron.builder.jvm.toolchain.internal;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.tyron.builder.api.model.ObjectFactory;

import java.io.File;

public class SpecificInstallationToolchainSpec extends DefaultToolchainSpec {

    private final File javaHome;

    public SpecificInstallationToolchainSpec(ObjectFactory factory, File javaHome) {
        super(factory);
        this.javaHome = javaHome;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    public File getJavaHome() {
        return javaHome;
    }

    @Override
    public String getDisplayName() {
        return MoreObjects.toStringHelper("SpecificToolchain").add("javaHome", javaHome).toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SpecificInstallationToolchainSpec that = (SpecificInstallationToolchainSpec) o;
        return Objects.equal(javaHome, that.javaHome);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(javaHome);
    }
}
