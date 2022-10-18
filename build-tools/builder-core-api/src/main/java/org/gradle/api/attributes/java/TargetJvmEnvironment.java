package org.gradle.api.attributes.java;

import org.gradle.api.Incubating;
import org.gradle.api.Named;
import org.gradle.api.attributes.Attribute;

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
    Attribute<TargetJvmEnvironment> TARGET_JVM_ENVIRONMENT_ATTRIBUTE = Attribute.of("org.gradle.jvm.environment", TargetJvmEnvironment.class);

    /**
     * A standard JVM environment (e.g. running on desktop or server machines).
     */
    String STANDARD_JVM = "standard-jvm";

    /**
     * An Android environment.
     */
    String ANDROID = "android";
}
