package com.tyron.builder.api.variant

import com.tyron.builder.api.artifact.Artifacts
import org.gradle.api.Incubating
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection

interface Component: ComponentIdentity {

    /**
     * Access to the variant's buildable artifacts for build customization.
     */
    val artifacts: Artifacts

    /**
     * Access to variant's source files.
     */
    @get:Incubating
    val sources: Sources

    /**
     * Access to the variant's java compilation options.
     */
    @get:Incubating
    val javaCompilation: JavaCompilation

//    @Deprecated("Use the instrumentation block.")
//    fun <ParamT : InstrumentationParameters> transformClassesWith(
//        classVisitorFactoryImplClass: Class<out AsmClassVisitorFactory<ParamT>>,
//        scope: InstrumentationScope,
//        instrumentationParamsConfig: (ParamT) -> Unit
//    )
//
//    @Deprecated("Use the instrumentation block.")
//    fun setAsmFramesComputationMode(mode: FramesComputationMode)
//
//    /**
//     * Access to the variant's instrumentation options.
//     */
//    val instrumentation: Instrumentation

    /**
     * Access to the variant's compile classpath.
     *
     * The returned [FileCollection] should not be resolved until execution time.
     */
    @get:Incubating
    val compileClasspath: FileCollection

    /**
     * Access to the variant's compile [Configuration]; for example, the debugCompileClasspath
     * [Configuration] for the debug variant.
     *
     * The returned [Configuration] should not be resolved until execution time.
     */
    @get:Incubating
    val compileConfiguration: Configuration

    /**
     * Access to the variant's runtime [Configuration]; for example, the debugRuntimeClasspath
     * [Configuration] for the debug variant.
     *
     * The returned [Configuration] should not be resolved until execution time.
     */
    @get:Incubating
    val runtimeConfiguration: Configuration

    /**
     * Access to the variant's annotation processor [Configuration]; for example, the
     * debugAnnotationProcessor [Configuration] for the debug variant.
     *
     * The returned [Configuration] should not be resolved until execution time.
     */
    @get:Incubating
    val annotationProcessorConfiguration: Configuration
}