package com.tyron.builder.api.attributes;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Named;

/**
 * Attributes to qualify the relation of this variant to the Test Suites which produced it.
 * <p>
 * This attribute is usually found on variants that have the {@link Category} attribute valued at {@link Category#DOCUMENTATION documentation}.
 *
 * @since 7.4
 */
@Incubating
public interface Verification extends Named {
    Attribute<Verification> TEST_SUITE_NAME_ATTRIBUTE = Attribute.of("com.tyron.builder.testsuitename", Verification.class);
    Attribute<Verification> TARGET_NAME_ATTRIBUTE = Attribute.of("com.tyron.builder.targetname", Verification.class);

    /**
     * The typical documentation for Java APIs
     */
    String JAVADOC = "javadoc";
}
