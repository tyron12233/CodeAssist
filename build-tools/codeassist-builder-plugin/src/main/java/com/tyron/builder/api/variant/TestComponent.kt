package com.tyron.builder.api.variant

import org.gradle.api.provider.MapProperty

interface TestComponent: Component {
    /**
     * [MapProperty] of the test component's manifest placeholders.
     *
     * Placeholders are organized with a key and a value. The value is a [String] that will be
     * used as is in the merged manifest.
     *
     * @return the [MapProperty] with keys as [String]
     */
    val manifestPlaceholders: MapProperty<String, String>
}