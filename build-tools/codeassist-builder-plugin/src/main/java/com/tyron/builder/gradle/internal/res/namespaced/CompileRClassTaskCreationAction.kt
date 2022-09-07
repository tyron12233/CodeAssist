package com.tyron.builder.gradle.internal.res.namespaced

import com.android.SdkConstants
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType.RUNTIME_R_CLASS_CLASSES
import com.tyron.builder.gradle.internal.scope.InternalArtifactType.RUNTIME_R_CLASS_SOURCES
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.tasks.factory.TaskCreationAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile

/**
 * Task to compile a directory containing R.java file(s) and jar the result.
 *
 * For namespaced libraries, there will be exactly one R.java file, but for applications there will
 * be a regenerated one per dependency.
 *
 * In the future, this might not call javac at all, but it needs to be profiled first.
 */
class CompileRClassTaskCreationAction(private val creationConfig: ComponentCreationConfig) :
    TaskCreationAction<JavaCompile>() {

    override val name: String
        get() = creationConfig.computeTaskName("compile", "FinalRClass")

    override val type: Class<JavaCompile>
        get() = JavaCompile::class.java

    override fun handleProvider(taskProvider: TaskProvider<JavaCompile>) {
        super.handleProvider(taskProvider)

        creationConfig.artifacts.setInitialProvider(
            taskProvider
        ) {  it.destinationDirectory  }.withName(SdkConstants.FD_RES).on(RUNTIME_R_CLASS_CLASSES)
    }

    override fun configure(task: JavaCompile) {
        val taskContainer: MutableTaskContainer = creationConfig.taskContainer
        task.dependsOn(taskContainer.preBuildTask)
        task.extensions.add("AGP_VARIANT_NAME", creationConfig.name)

        task.classpath = task.project.files()
        if (creationConfig.componentType.isTestComponent || creationConfig.componentType.isApk) {
            task.source(creationConfig.artifacts.get(RUNTIME_R_CLASS_SOURCES))
        }
    }
}
