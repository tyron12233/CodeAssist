package com.tyron.builder.jvm.toolchain.internal;

import com.google.common.base.Objects;
import org.apache.commons.lang3.StringUtils;
import com.tyron.builder.internal.jvm.inspection.JvmVendor;
import com.tyron.builder.jvm.toolchain.JvmVendorSpec;

import java.util.function.Predicate;

public class DefaultJvmVendorSpec extends JvmVendorSpec implements Predicate<JavaToolchain> {

    private static final JvmVendorSpec ANY = new DefaultJvmVendorSpec(v -> true, "any");

    private final Predicate<JvmVendor> matcher;
    private final String description;

    public static JvmVendorSpec matching(String match) {
        return new DefaultJvmVendorSpec(vendor -> StringUtils.containsIgnoreCase(vendor.getRawVendor(), match), "matching('" + match + "')");
    }

    public static JvmVendorSpec of(JvmVendor.KnownJvmVendor knownVendor) {
        return new DefaultJvmVendorSpec(vendor -> vendor.getKnownVendor() == knownVendor, knownVendor.toString());
    }

    public static JvmVendorSpec any() {
        return ANY;
    }

    private DefaultJvmVendorSpec(Predicate<JvmVendor> predicate, String description) {
        this.matcher = predicate;
        this.description = description;
    }

    @Override
    public boolean test(JavaToolchain toolchain) {
        final JvmVendor vendor = toolchain.getMetadata().getVendor();
        return test(vendor);
    }

    public boolean test(JvmVendor vendor) {
        return matcher.test(vendor);
    }

    @Override
    public String toString() {
        return description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultJvmVendorSpec that = (DefaultJvmVendorSpec) o;
        return Objects.equal(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(description);
    }
}
