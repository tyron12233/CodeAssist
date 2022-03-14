package com.tyron.viewbinding.task

import com.tyron.builder.project.Project
import com.tyron.builder.project.api.AndroidModule
import java.io.File

// TODO
class InjectViewBindingTask private constructor(
    val project: Project,
    val module: AndroidModule,
) {

    fun inject(consumer: (File) -> Unit) {

    }

    companion object {
        @JvmStatic
        fun inject(project: Project) {
            inject(project, project.mainModule as AndroidModule)
        }

        @JvmStatic
        fun inject(project: Project, module: AndroidModule) {

        }
    }

}
