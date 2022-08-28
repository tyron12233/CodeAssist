package com.tyron.builder.gradle.tasks

import com.android.tools.r8.internal.it
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.TaskCreationAction
import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.util.PatternSet


/**
 * [TaskCreationAction] for the [JavaCompile] task.
 *
 * Note that when Kapt is not used (e.g., in Java-only projects), [JavaCompile] performs both
 * annotation processing and compilation. When Kapt is used (e.g., in most Kotlin-only or hybrid
 * Kotlin-Java projects), [JavaCompile] performs compilation only, without annotation processing.
 */
class JavaCompileCreationAction(
    private val creationConfig: ComponentCreationConfig,
    objectFactory: ObjectFactory,
    private val usingKapt: Boolean
) : TaskCreationAction<JavaCompile>() {

    private val dataBindingArtifactDir = objectFactory.directoryProperty()
    private val dataBindingExportClassListFile = objectFactory.fileProperty()

    override val name: String
    get() = "compileDebugJavaWithJavac"
//        get() = creationConfig.computeTaskName("compile", "JavaWithJavac")

    override val type: Class<JavaCompile>
        get() = JavaCompile::class.java

    override fun handleProvider(taskProvider: TaskProvider<JavaCompile>) {
        super.handleProvider(taskProvider)

//        creationConfig.taskContainer.javacTask = taskProvider

        val artifacts = creationConfig.artifacts

        artifacts
            .setInitialProvider(taskProvider) { it.destinationDirectory }
            .withName("classes")
            .on(InternalArtifactType.JAVAC)

        artifacts
            .setInitialProvider(taskProvider) { it.options.generatedSourceOutputDirectory }
            // Setting a name is not required, but a lot of AGP and IDE tests are assuming this name
            // so we leave it here for now.
            .withName(AP_GENERATED_SOURCES_DIR_NAME)
            .on(InternalArtifactType.AP_GENERATED_SOURCES)

//        if (creationConfig.buildFeatures.dataBinding) {
//            // Register data binding artifacts as outputs. There are 2 ways to do this:
//            //    (1) Register with JavaCompile when Kapt is not used, and register with Kapt when
//            //        Kapt is used.
//            //    (2) Always register with JavaCompile, and when Kapt is used, replace them with
//            //        Kapt.
//            // The first way is simpler but unfortunately will break the publishing of the artifacts
//            // because publishing takes place before the registration with Kapt (bug 161814391).
//            // Therefore, we'll have to do it the second way.
//            registerDataBindingOutputs(
//                dataBindingArtifactDir,
//                dataBindingExportClassListFile,
//                creationConfig.componentType.isExportDataBindingClassList,
//                taskProvider,
//                artifacts,
//                forJavaCompile = true
//            )
//        }
    }


    override fun configure(task: JavaCompile) {
//        task.dependsOn(creationConfig.taskContainer.preBuildTask)
//        task.extensions.add(PROPERTY_VARIANT_NAME_KEY, creationConfig.name)

        task.configureProperties(creationConfig, task)
        task.source = computeJavaSource(creationConfig, task.project)
        task.options.isIncremental = true
    }

    fun computeJavaSource(creationConfig: ComponentCreationConfig, project: Project): FileTree {
        // do not resolve the provider before execution phase, b/117161463.
        val sourcesToCompile = project.extensions.getByType(JavaPluginExtension::class.java)
            .sourceSets
            .getByName("main").allJava.asFileTree
        // creationConfig.sources.java.getAsFileTrees()
        // Include only java sources, otherwise we hit b/144249620.
        val javaSourcesFilter = PatternSet().include("**/*.java")
        return project.files(sourcesToCompile).asFileTree.matching(javaSourcesFilter)
    }
}

private const val AP_GENERATED_SOURCES_DIR_NAME = "out"
private const val ANDROIDX_ROOM_ROOM_COMPILER = "androidx.room:room-compiler"
private const val PARAMETERS = "-parameters"