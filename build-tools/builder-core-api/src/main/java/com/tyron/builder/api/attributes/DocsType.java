package com.tyron.builder.api.attributes;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Named;

/**
 * Attributes to qualify the type of documentation.
 * <p>
 * This attribute is usually found on variants that have the {@link Category} attribute valued at {@link Category#DOCUMENTATION documentation}.
 *
 * @since 5.6
 */
public interface DocsType extends Named {
    Attribute<DocsType> DOCS_TYPE_ATTRIBUTE = Attribute.of("com.tyron.builder.docstype", DocsType.class);

    /**
     * The typical documentation for Java APIs
     */
    String JAVADOC = "javadoc";

    /**
     * The source files of the module
     */
    String SOURCES = "sources";

    /**
     * A user manual
     */
    String USER_MANUAL = "user-manual";

    /**
     * Samples illustrating how to use the software module
     */
    String SAMPLES = "samples";

    /**
     * The typical documentation for native APIs
     */
    String DOXYGEN = "doxygen";

    /**
     * Binary results of running tests containing pass/fail information
     *
     * @since 7.4
     */
    @Incubating
    String TEST_RESULTS = "test-results-bin";

    /**
     * Binary test coverage data gathered by JaCoCo
     *
     * @since 7.4
     */
    @Incubating
    String JACOCO_COVERAGE = "jacoco-coverage-bin";
}
