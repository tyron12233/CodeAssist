package com.tyron.builder.gradle.internal.tasks.factory

import com.tyron.builder.gradle.internal.services.BaseServices
import org.gradle.api.component.SoftwareComponentFactory

class TaskManagerConfigImpl(
    private val services: BaseServices,
    override val componentFactory: SoftwareComponentFactory,
): TaskManagerConfig {

    override val dataBindingBuilder = Any()

//            DataBindingBuilder = DataBindingBuilder().also {
//        it.setPrintMachineReadableOutput(
//            SyncOptions.getErrorFormatMode(services.projectOptions) == SyncOptions.ErrorFormatMode.MACHINE_PARSABLE)
//    }
}
