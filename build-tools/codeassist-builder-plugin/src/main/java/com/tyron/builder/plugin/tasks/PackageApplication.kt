package com.tyron.builder.plugin.tasks

import com.android.sdklib.build.ApkBuilder
import com.tyron.builder.internal.component.ApkCreationConfig
import com.tyron.builder.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.tasks.IncrementalTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.work.InputChanges
import java.io.File

abstract class PackageApplication : IncrementalTask() {

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    override fun doTaskAction(input: InputChanges?) {

    }


    class CreationAction(
        creationConfig: ApkCreationConfig
    ) : VariantTaskCreationAction<PackageApplication, ApkCreationConfig>(creationConfig) {

        override val name = "packageDebug"

        override val type = PackageApplication::class.java
    }

}