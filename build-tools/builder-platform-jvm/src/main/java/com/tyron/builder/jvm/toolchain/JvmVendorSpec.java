package com.tyron.builder.jvm.toolchain;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.internal.jvm.inspection.JvmVendor.KnownJvmVendor;
import com.tyron.builder.jvm.toolchain.internal.DefaultJvmVendorSpec;

/**
 * Represents a filter for a vendor of a Java Virtual Machine implementation.
 *
 * @since 6.8
 */
public abstract class JvmVendorSpec {

    /**
     * A constant for using <a href="https://projects.eclipse.org/projects/adoptium">Eclipse Adoptium</a> as the JVM vendor.
     *
     * @since 7.4
     */
    @Incubating
    public static final JvmVendorSpec ADOPTIUM = matching(KnownJvmVendor.ADOPTIUM);
    public static final JvmVendorSpec ADOPTOPENJDK = matching(KnownJvmVendor.ADOPTOPENJDK);
    public static final JvmVendorSpec AMAZON = matching(KnownJvmVendor.AMAZON);
    public static final JvmVendorSpec APPLE = matching(KnownJvmVendor.APPLE);
    public static final JvmVendorSpec AZUL = matching(KnownJvmVendor.AZUL);
    public static final JvmVendorSpec BELLSOFT = matching(KnownJvmVendor.BELLSOFT);

    /**
     * A constant for using <a href="https://www.graalvm.org/">GraalVM</a> as the JVM vendor.
     *
     * @since 7.1
     */
    @Incubating
    public static final JvmVendorSpec GRAAL_VM = matching(KnownJvmVendor.GRAAL_VM);

    public static final JvmVendorSpec HEWLETT_PACKARD = matching(KnownJvmVendor.HEWLETT_PACKARD);
    public static final JvmVendorSpec IBM = matching(KnownJvmVendor.IBM);
    /**
     * A constant for using <a href="https://developer.ibm.com/languages/java/semeru-runtimes/">IBM Semeru Runtimes</a> as the JVM vendor.
     *
     * @since 7.4
     */
    @Incubating
    public static final JvmVendorSpec IBM_SEMERU = matching(KnownJvmVendor.IBM_SEMERU);

    /**
     * A constant for using <a href="https://www.microsoft.com/openjdk">Microsoft OpenJDK</a> as the JVM vendor.
     *
     * @since 7.3
     */
    @Incubating
    public static final JvmVendorSpec MICROSOFT = matching(KnownJvmVendor.MICROSOFT);
    public static final JvmVendorSpec ORACLE = matching(KnownJvmVendor.ORACLE);
    public static final JvmVendorSpec SAP = matching(KnownJvmVendor.SAP);

    /**
     * Returns a vendor spec that matches a VM by its vendor.
     * <p>
     * A VM is determined eligible if the system property <code>java.vendor</code> contains
     * the given match string. The comparison is done case-insensitive.
     * </p>
     * @param match the sequence to search for
     * @return a new filter object
     */
    public static JvmVendorSpec matching(String match) {
        return DefaultJvmVendorSpec.matching(match);
    }

    private static JvmVendorSpec matching(KnownJvmVendor vendor) {
        return DefaultJvmVendorSpec.of(vendor);
    }

}
