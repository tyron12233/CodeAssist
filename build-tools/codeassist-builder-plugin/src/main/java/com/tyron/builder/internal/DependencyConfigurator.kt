package com.tyron.builder.internal

import com.tyron.builder.internal.dependency.ClassesDirToClassesTransform
import com.tyron.builder.internal.dependency.GenericTransformParameters
import com.tyron.builder.internal.dependency.IdentityTransform
import com.tyron.builder.internal.publishing.AndroidArtifacts
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformSpec
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.artifacts.ArtifactAttributes

class DependencyConfigurator(
    private val project: Project
){
    fun configureGeneralTransforms(
        namespacedAndroidResources: Boolean,
    ): DependencyConfigurator {


        // The aars/jars may need to be processed (e.g., jetified to AndroidX) before they can be
        // used
        val autoNamespaceDependencies =
            namespacedAndroidResources &&  false//projectOptions[BooleanOption.CONVERT_NON_NAMESPACED_DEPENDENCIES]

        val jetifiedAarOutputType = if (autoNamespaceDependencies) {
            AndroidArtifacts.ArtifactType.MAYBE_NON_NAMESPACED_PROCESSED_AAR
        } else {
            AndroidArtifacts.ArtifactType.PROCESSED_AAR
        }

        val jetifierEnabled = false
        if (jetifierEnabled) {
            // TODO: Support Jetifier
            TODO()
        } else {
            registerTransform(
                IdentityTransform::class.java,
                AndroidArtifacts.ArtifactType.AAR,
                jetifiedAarOutputType
            )
            registerTransform(
                IdentityTransform::class.java,
                AndroidArtifacts.ArtifactType.JAR,
                AndroidArtifacts.ArtifactType.PROCESSED_JAR
            )
        }

        // Transform to go from external jars to CLASSES and JAVA_RES artifacts. This returns the
        // same exact file but with different types, since a jar file can contain both.
        for (classesOrResources in arrayOf(
            AndroidArtifacts.ArtifactType.CLASSES_JAR,
            AndroidArtifacts.ArtifactType.JAVA_RES
        )) {
            registerTransform(
                IdentityTransform::class.java,
                AndroidArtifacts.ArtifactType.PROCESSED_JAR,
                classesOrResources
            )
        }

        // The Kotlin Kapt plugin should query for PROCESSED_JAR, but it is currently querying for
        // JAR, so we need to have the workaround below to make it get PROCESSED_JAR. See
        // http://issuetracker.google.com/111009645.
        project.configurations.all { configuration: Configuration ->
            if (configuration.name.startsWith("kapt")) {
                configuration
                    .attributes
                    .attribute(
                        ArtifactAttributes.ARTIFACT_FORMAT,
                        AndroidArtifacts.ArtifactType.PROCESSED_JAR.type
                    )
            }
        }

        // From an Android library subproject, there are 2 transform flows to CLASSES:
        //     1. CLASSES_DIR -> CLASSES
        //     2. CLASSES_JAR -> CLASSES
        // From a Java library subproject, there are also 2 transform flows to CLASSES:
        //     1. JVM_CLASS_DIRECTORY -> CLASSES
        //     2. JAR -> PROCESSED_JAR -> `CLASSES_JAR -> CLASSES
        registerTransform(
            ClassesDirToClassesTransform::class.java,
            AndroidArtifacts.ArtifactType.CLASSES_DIR,
            AndroidArtifacts.ArtifactType.CLASSES
        )
        registerTransform(
            IdentityTransform::class.java,
            AndroidArtifacts.ArtifactType.CLASSES_JAR,
            AndroidArtifacts.ArtifactType.CLASSES
        )
        registerTransform(
            IdentityTransform::class.java,
            ArtifactTypeDefinition.JVM_CLASS_DIRECTORY,
            AndroidArtifacts.ArtifactType.CLASSES.type
        ) { params ->
            params.acceptNonExistentInputFile.setDisallowChanges(true)
        }
        return this
    }

    private fun <T : GenericTransformParameters> registerTransform(
        transformClass: Class<out TransformAction<T>>,
        fromArtifactType: AndroidArtifacts.ArtifactType,
        toArtifactType: AndroidArtifacts.ArtifactType,
        parametersSetter: ((T) -> Unit)? = null
    ) {
        registerTransform(
            transformClass,
            fromArtifactType.type,
            toArtifactType.type,
            parametersSetter
        )
    }

    private fun <T : GenericTransformParameters> registerTransform(
        transformClass: Class<out TransformAction<T>>,
        fromArtifactType: String,
        toArtifactType: String,
        parametersSetter: ((T) -> Unit)? = null
    ) {
        project.dependencies.registerTransform(
            transformClass
        ) { spec: TransformSpec<T> ->
            spec.from.attribute(ArtifactAttributes.ARTIFACT_FORMAT, fromArtifactType)
            spec.to.attribute(ArtifactAttributes.ARTIFACT_FORMAT, toArtifactType)
            spec.parameters.projectName.setDisallowChanges(project.name)
            parametersSetter?.let { it(spec.parameters) }
        }
    }
}