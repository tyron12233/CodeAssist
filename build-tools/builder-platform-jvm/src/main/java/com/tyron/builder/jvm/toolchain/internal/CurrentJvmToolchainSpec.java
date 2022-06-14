package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.jvm.Jvm;
import com.tyron.builder.jvm.toolchain.JavaLanguageVersion;

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
