package com.tyron.builder.jvm.toolchain;

/**
 * Represents a filter for a implementation of a Java Virtual Machine.
 *
 * @since 6.8
 */
public final class JvmImplementation {

    /**
     * Vendor-specific virtual machine implementation.
     *
     * Acts as a placeholder and matches any implementation from any vendor.
     * Usually used to override specific implementation requests.
     */
    public static final JvmImplementation VENDOR_SPECIFIC = new JvmImplementation("vendor-specific");

    /**
     * Eclipse OpenJ9 (previously known as IBM J9) virtual machine implementation.
     *
     * Matches only virtual machine implementations using the OpenJ9/IBM J9 runtime engine.
     */
    public static final JvmImplementation J9 = new JvmImplementation("J9");

    private final String displayName;

    private JvmImplementation(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
