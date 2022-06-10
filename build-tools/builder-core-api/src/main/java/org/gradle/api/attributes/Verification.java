package org.gradle.api.attributes;

import org.gradle.api.Incubating;
import org.gradle.api.Named;

/**
 * Attributes to qualify the relation of this variant to the Test Suites which produced it.
 * <p>
 * This attribute is usually found on variants that have the {@link Category} attribute valued at {@link Category#DOCUMENTATION documentation}.
 *
 * @since 7.4
 */
@Incubating
public interface Verification extends Named {
    Attribute<Verification> TEST_SUITE_NAME_ATTRIBUTE = Attribute.of("org.gradle.testsuitename", Verification.class);
    Attribute<Verification> TARGET_NAME_ATTRIBUTE = Attribute.of("org.gradle.targetname", Verification.class);

    /**
     * The typical documentation for Java APIs
     */
    String JAVADOC = "javadoc";
}
