package com.tyron.builder.gradle.internal.tasks

import org.gradle.api.tasks.TaskAction

abstract class NonIncrementalTask : AndroidVariantTask() {

    @Throws(Exception::class)
    protected abstract fun doTaskAction()

    @TaskAction
    fun taskAction() {
        doTaskAction()
    }
}