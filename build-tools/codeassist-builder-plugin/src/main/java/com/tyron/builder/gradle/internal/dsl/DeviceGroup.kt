package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.Device
import com.tyron.builder.api.dsl.DeviceGroup
import com.tyron.builder.gradle.internal.services.DslServices
import org.gradle.api.DomainObjectSet
import javax.inject.Inject

open class DeviceGroup @Inject constructor(dslServices: DslServices, private val name: String):
    DeviceGroup {

    override fun getName(): String = name

    override val targetDevices: DomainObjectSet<Device> =
        dslServices.domainObjectSet(Device::class.java)
}