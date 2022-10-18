package org.gradle.api.attributes;

import org.gradle.api.Incubating;
import org.gradle.api.Named;

/**
 * Attribute to be used on variants containing the output verification checks (Test data, Jacoco results, etc) which specify the
 * type of verification data.
 * <p>
 * This attribute is usually found on variants that have the {@link Category} attribute valued at {@link Category#VERIFICATION}.
 *
 * @since 7.4
 */
@Incubating
public interface VerificationType extends Named {
    Attribute<VerificationType> VERIFICATION_TYPE_ATTRIBUTE = Attribute.of("org.gradle.verificationtype", VerificationType.class);

    /**
     * A list of directories containing source code, includes code in transitive dependencies
     */
    String MAIN_SOURCES = "main-sources";

    /**
     * Binary results of running tests containing pass/fail information
     */
    String JACOCO_RESULTS = "jacoco-coverage";

    /**
     * Binary test coverage data gathered by JaCoCo
     */
    String TEST_RESULTS = "test-results";
}
