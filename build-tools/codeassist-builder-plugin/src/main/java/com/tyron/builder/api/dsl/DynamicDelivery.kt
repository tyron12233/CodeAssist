package com.tyron.builder.api.dsl

import org.gradle.api.Incubating
import org.gradle.api.provider.Property

/** DSL object to configure dynamic delivery of an asset pack. */
@Incubating
interface DynamicDelivery {
    /**
     * Identifies the delivery type {install-time, fast-follow, on-demand}
     * when the asset pack is used with a persistent app.
     */
    val deliveryType: Property<String>
    /**
     * Identifies the delivery type {on-demand}
     * when the asset pack is used with an instant app.
     */
    val instantDeliveryType: Property<String>
}