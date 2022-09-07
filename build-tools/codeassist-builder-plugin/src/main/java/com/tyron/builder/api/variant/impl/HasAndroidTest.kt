package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.component.impl.AndroidTestImpl

/**
 * Internal marker interface for [VariantImpl] that potentially has associated android tests.
 */
interface HasAndroidTest {
    var androidTest: AndroidTestImpl?
}