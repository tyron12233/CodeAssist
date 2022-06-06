package com.tyron.builder.jvm.toolchain.internal;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.jvm.toolchain.JavaLanguageVersion;
import com.tyron.builder.jvm.toolchain.JvmImplementation;
import com.tyron.builder.jvm.toolchain.JvmVendorSpec;

import javax.inject.Inject;

public class DefaultToolchainSpec implements ToolchainSpecInternal {

    private final Property<JavaLanguageVersion> languageVersion;
    private final Property<JvmVendorSpec> vendor;
    private final Property<JvmImplementation> implementation;

    @Inject
    public DefaultToolchainSpec(ObjectFactory factory) {
        this.languageVersion = factory.property(JavaLanguageVersion.class);
        this.vendor = factory.property(JvmVendorSpec.class).convention(DefaultJvmVendorSpec.any());
        this.implementation = factory.property(JvmImplementation.class).convention(JvmImplementation.VENDOR_SPECIFIC);
    }

    @Override
    public Property<JavaLanguageVersion> getLanguageVersion() {
        return languageVersion;
    }

    @Override
    public Property<JvmVendorSpec> getVendor() {
        return vendor;
    }

    @Override
    public Property<JvmImplementation> getImplementation() {
        return implementation;
    }

    @Override
    public boolean isConfigured() {
        return languageVersion.isPresent();
    }

    @Override
    public String getDisplayName() {
        final MoreObjects.ToStringHelper builder = MoreObjects.toStringHelper("");
        builder.omitNullValues();
        builder.add("languageVersion", languageVersion.map(JavaLanguageVersion::toString).getOrElse("unspecified"));
        builder.add("vendor", vendor.map(JvmVendorSpec::toString).getOrNull());
        builder.add("implementation", implementation.map(JvmImplementation::toString).getOrNull());
        return builder.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultToolchainSpec that = (DefaultToolchainSpec) o;
        return Objects.equal(languageVersion.get(), that.languageVersion.get()) &&
            Objects.equal(vendor.get(), that.vendor.get()) &&
            Objects.equal(implementation.get(), that.implementation.get());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(languageVersion.get(), vendor.get(), implementation.get());
    }

}
