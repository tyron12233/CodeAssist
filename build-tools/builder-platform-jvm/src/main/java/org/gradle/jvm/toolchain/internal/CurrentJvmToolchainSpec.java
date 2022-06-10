package org.gradle.jvm.toolchain.internal;

import org.gradle.api.model.ObjectFactory;
import org.gradle.internal.jvm.Jvm;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

public class CurrentJvmToolchainSpec extends DefaultToolchainSpec {

    public CurrentJvmToolchainSpec(ObjectFactory factory) {
        super(factory);
        getLanguageVersion().set(JavaLanguageVersion.of(Jvm.current().getJavaVersion().getMajorVersion()));
    }

    @Override
    public String getDisplayName() {
        return "CurrentJVM" + super.getDisplayName();
    }
}
