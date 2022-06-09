package com.tyron.builder.api.attributes;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Named;

/**
 * Attributes to qualify the type of testing a Test Suite will perform
 * <p>
 * This attribute is usually found on variants that have the {@link Category} attribute valued at {@link Usage#VERIFICATION verification}.
 *
 * @since 7.4
 */
@Incubating
public interface TestType extends Named {
    Attribute<TestType> TEST_TYPE_ATTRIBUTE = Attribute.of("com.tyron.builder.testsuitetype", TestType.class);

    /**
     * Unit tests, the default type of Test Suite
     */
    String UNIT_TESTS = "unit-tests";

    String INTEGRATION_TESTS = "integration-tests";

    /**
     * Functional tests, will be added automatically when initializing a new plugin project
     */
    String FUNCTIONAL_TESTS = "functional-tests";

    String PERFORMANCE_TESTS = "performance-tests";
}
