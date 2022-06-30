package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.internal.jvm.inspection.JvmInstallationMetadata;
import com.tyron.builder.jvm.toolchain.JavaToolchainSpec;
import com.tyron.builder.jvm.toolchain.JvmImplementation;

import java.util.function.Predicate;

public class ToolchainMatcher implements Predicate<JavaToolchain> {

    private final JavaToolchainSpec filter;

    public ToolchainMatcher(JavaToolchainSpec filter) {
        this.filter = filter;
    }

    @Override
    public boolean test(JavaToolchain toolchain) {
        return languagePredicate().and(vendorPredicate()).and(implementationPredicate()).test(toolchain);
    }

    private Predicate<JavaToolchain> languagePredicate() {
        return toolchain -> toolchain.getLanguageVersion().equals(filter.getLanguageVersion().get());
    }

    private Predicate<? super JavaToolchain> implementationPredicate() {
        return toolchain -> {
            final boolean isJ9Vm = toolchain.getMetadata().hasCapability(JvmInstallationMetadata.JavaInstallationCapability.J9_VIRTUAL_MACHINE);
            final boolean j9Requested = filter.getImplementation().get() == JvmImplementation.J9;
            return j9Requested == isJ9Vm;
        };
    }

    @SuppressWarnings("unchecked")
    private Predicate<? super JavaToolchain> vendorPredicate() {
        return (Predicate<? super JavaToolchain>) filter.getVendor().get();
    }
}
