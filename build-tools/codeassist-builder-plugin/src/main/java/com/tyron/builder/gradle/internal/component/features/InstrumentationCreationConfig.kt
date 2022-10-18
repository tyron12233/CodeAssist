package com.tyron.builder.gradle.internal.component.features

//import com.tyron.builder.api.instrumentation.AsmClassVisitorFactory
//import com.tyron.builder.api.instrumentation.FramesComputationMode
//import com.tyron.builder.api.variant.Instrumentation
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory

/**
 * Creation config for components that support instrumenting bytecode.
 *
 * To use this in a task that requires instrumentation support, use
 * [com.tyron.builder.gradle.internal.tasks.factory.features.InstrumentationTaskCreationAction].
 * Otherwise, access the nullable property on the component
 * [com.tyron.builder.gradle.internal.component.ComponentCreationConfig.instrumentationCreationConfig].
 */
interface InstrumentationCreationConfig {
//    val instrumentation: Instrumentation
//
//    val asmFramesComputationMode: FramesComputationMode

    val projectClassesAreInstrumented: Boolean
    val dependenciesClassesAreInstrumented: Boolean

    val projectClassesPostInstrumentation: FileCollection
    fun getDependenciesClassesJarsPostInstrumentation(
        scope: AndroidArtifacts.ArtifactScope
    ): FileCollection

//    val registeredProjectClassesVisitors: List<AsmClassVisitorFactory<*>>
//    val registeredDependenciesClassesVisitors: List<AsmClassVisitorFactory<*>>

    fun configureAndLockAsmClassesVisitors(objectFactory: ObjectFactory)
}
