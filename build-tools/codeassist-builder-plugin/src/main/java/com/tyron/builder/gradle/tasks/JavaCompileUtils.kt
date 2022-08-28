package com.tyron.builder.gradle.tasks

import com.tyron.builder.BuildModule
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile

/**
 * Configures a [JavaCompile] task with necessary properties to perform compilation and/or
 * annotation processing.
 *
 * @see [JavaCompile.configurePropertiesForAnnotationProcessing]
 */
fun JavaCompile.configureProperties(creationConfig: ComponentCreationConfig, task: JavaCompile) {
//    val compileOptions = creationConfig.global.compileOptions

//    if (compileOptions.sourceCompatibility.isJava9Compatible) {
////        checkSdkCompatibility(creationConfig.global.compileSdkHashString, creationConfig.services.issueReporter)
//        checkNotNull(task.project.configurations.findByName(CONFIG_NAME_ANDROID_JDK_IMAGE)) {
//            "The $CONFIG_NAME_ANDROID_JDK_IMAGE configuration must exist for Java 9+ sources."
//        }
//
//        val jdkImage = getJdkImageFromTransform(
//            creationConfig.services,
//            task.javaCompiler.orNull
//        )
//
//        this.options.mentProviders.add(JdkImageInput(jdkImage))
//        // Make Javac generate legacy bytecode for string concatenation, see b/65004097
//        this.options.compilerArgs.add("-XDstringConcat=inline")
//        this.classpath = project.files(
//            // classes(e.g. android.jar) that were previously passed through bootstrapClasspath need to be provided
//            // through classpath
//            creationConfig.global.bootClasspath,
//            creationConfig.compileClasspath
//        )
//    } else {
    this.options.bootstrapClasspath =
        task.project.files(BuildModule.getAndroidJar(), BuildModule.getLambdaStubs())
//    this.classpath = creationConfig.compileClasspath

    val classesLibraryElements =
        project.objects.named(
            LibraryElements::class.java,
            LibraryElements.CLASSES
        )

    val compileClasspath = project.configurations.getByName("compileClasspath")

    classpath = project.configurations.detachedConfiguration().apply {
        dependencies.addAll(compileClasspath.allDependencies.map(project.dependencies::create))

        attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_API))
        attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, AndroidArtifacts.ArtifactType.PROCESSED_JAR.type)
    }.incoming.artifacts.artifactFiles

    val byType = task.project.extensions.getByType(JavaPluginExtension::class.java)

    this.sourceCompatibility = byType.sourceCompatibility.toString()
    this.targetCompatibility = byType.sourceCompatibility.toString()
    this.options.encoding = "utf-8"
}