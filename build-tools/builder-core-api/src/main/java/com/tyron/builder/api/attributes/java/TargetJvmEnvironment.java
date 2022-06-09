package com.tyron.builder.api.attributes.java;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Named;
import com.tyron.builder.api.attributes.Attribute;

/**
 * Represents the target JVM environment. Typically, a standard JVM or Android.
 * This attribute can be used by libraries to indicate that a certain variant is better suited for
 * a certain JVM environment. It does however NOT strictly require environments to match, as the
 * general assumption is that Java libraries can also run on environments they are not optimized for.
 *
 * @since 7.0
 */
@Incubating
public interface TargetJvmEnvironment extends Named {
    Attribute<TargetJvmEnvironment> TARGET_JVM_ENVIRONMENT_ATTRIBUTE = Attribute.of("com.tyron.builder.jvm.environment", TargetJvmEnvironment.class);

    /**
     * A standard JVM environment (e.g. running on desktop or server machines).
     */
    String STANDARD_JVM = "standard-jvm";

    /**
     * An Android environment.
     */
    String ANDROID = "android";
}
