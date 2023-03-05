package com.tyron.builder.api.dsl

import org.gradle.api.DomainObjectSet
import org.gradle.api.Incubating
import org.gradle.api.Named

/**
 * A group of devices to be run with tests using the Unified Test Platform.
 */
@Incubating
interface DeviceGroup: Named {
    val targetDevices: DomainObjectSet<Device>
}