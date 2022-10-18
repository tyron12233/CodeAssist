package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.component.impl.TestFixturesImpl

/**
 * Internal marker interface for [VariantImpl] that potentially has associated test fixtures.
 */
interface HasTestFixtures {
    var testFixtures: TestFixturesImpl?
}