package com.tyron.builder.api.attributes.java;

import com.tyron.builder.api.attributes.Attribute;

/**
 * Represents the target version of a Java library or platform. The target level is expected to correspond
 * to a Java platform version number (integer). For example, "5" for Java 5, "8" for Java 8, or "11" for Java 11.
 *
 * @since 5.3
 */
public interface TargetJvmVersion {

    /**
     * The minimal target version for a Java library. Any consumer below this version would not be able to consume it.
     */
    Attribute<Integer> TARGET_JVM_VERSION_ATTRIBUTE = Attribute.of("com.tyron.builder.jvm.version", Integer.class);
}
