package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.api.transform.QualifiedContent
import com.tyron.builder.gradle.internal.InternalScope
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.component.ApplicationCreationConfig
import com.tyron.builder.gradle.internal.component.TestComponentCreationConfig
import com.tyron.builder.gradle.internal.pipeline.StreamFilter
import com.tyron.builder.gradle.internal.pipeline.TransformManager
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.publishing.PublishingSpecs
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.options.BooleanOption
import org.gradle.api.file.FileCollection

class ClassesClasspathUtils(
    val creationConfig: ApkCreationConfig,
    enableDexingArtifactTransform: Boolean,
    classesAlteredTroughVariantAPI: Boolean,
) {

    val projectClasses: FileCollection

    val subProjectsClasses: FileCollection
    val externalLibraryClasses: FileCollection
    val mixedScopeClasses: FileCollection
    val desugaringClasspathClasses: FileCollection

    // Difference between this property and desugaringClasspathClasses is that for the test
    // variant, this property does not contain tested project code, allowing us to have more
    // cache hits when using artifact transforms.
    val desugaringClasspathForArtifactTransforms: FileCollection
    val dexExternalLibsInArtifactTransform: Boolean

    init {
        @Suppress("DEPRECATION") // Legacy support
        val classesFilter =
            StreamFilter { types, _ -> QualifiedContent.DefaultContentType.CLASSES in types }

        val transformManager = creationConfig.transformManager

        val jacocoTransformEnabled =
            creationConfig.isAndroidTestCoverageEnabled &&
                    !creationConfig.componentType.isForTesting

        // The source of project classes depend on whether; Jacoco instrumentation is enabled,
        // instrumentation is collected using an artifact transform and or
        // the user registers a transform using the legacy Transform
        // [com.tyron.builder.api.transform.Transform].
        //
        // Cases:
        // (1) Jacoco Transform is enabled and there are no registered transforms:
        //     then: Provide the project classes from the artifacts produced by the JacocoTask
        // (2) Jacoco Transform is enabled and a legacy transform is registered:
        //     then: No project classes will be provided. Rather, artifacts produced by the
        //           JacocoTask will be set as the mixed scope classes.
        // (3) No Jacoco Transforms:
        //      then: Provide the project classes from the legacy transform API classes.

        projectClasses = if (jacocoTransformEnabled && !classesAlteredTroughVariantAPI) {
            creationConfig.services.fileCollection(
                creationConfig.artifacts.get(
                    InternalArtifactType.JACOCO_INSTRUMENTED_CLASSES
                ),
                creationConfig.services.fileCollection(
                    creationConfig.artifacts.get(
                        InternalArtifactType.JACOCO_INSTRUMENTED_JARS
                    )
                ).asFileTree
            )
        } else {
            @Suppress("DEPRECATION") // Legacy support
            transformManager.getPipelineOutputAsFileCollection(
                { _, scopes -> scopes == setOf(QualifiedContent.Scope.PROJECT) },
                classesFilter
            )
        }

        @Suppress("DEPRECATION") // Legacy support
        val desugaringClasspathScopes: MutableSet<QualifiedContent.ScopeType> =
            mutableSetOf(QualifiedContent.Scope.PROVIDED_ONLY)
        if (classesAlteredTroughVariantAPI) {
            subProjectsClasses = creationConfig.services.fileCollection()
            externalLibraryClasses = creationConfig.services.fileCollection()
            mixedScopeClasses = creationConfig.services.fileCollection()
            dexExternalLibsInArtifactTransform = false
        } else if (enableDexingArtifactTransform) {
            subProjectsClasses = creationConfig.services.fileCollection()
            externalLibraryClasses = creationConfig.services.fileCollection()
            mixedScopeClasses = creationConfig.services.fileCollection()
            dexExternalLibsInArtifactTransform = false

            @Suppress("DEPRECATION") // Legacy support
            run {
                desugaringClasspathScopes.add(QualifiedContent.Scope.EXTERNAL_LIBRARIES)
                desugaringClasspathScopes.add(QualifiedContent.Scope.TESTED_CODE)
                desugaringClasspathScopes.add(QualifiedContent.Scope.SUB_PROJECTS)
            }
        } else if ((creationConfig as? ApplicationCreationConfig)?.consumesFeatureJars == true) {
            subProjectsClasses = creationConfig.services.fileCollection()
            externalLibraryClasses = creationConfig.services.fileCollection()
            dexExternalLibsInArtifactTransform = false

            // Get all classes from the scopes we are interested in.
            mixedScopeClasses = transformManager.getPipelineOutputAsFileCollection(
                { _, scopes ->
                    scopes.isNotEmpty() && scopes.subtract(
                        TransformManager.SCOPE_FULL_WITH_FEATURES
                    ).isEmpty()
                },
                classesFilter
            )
            @Suppress("DEPRECATION") // Legacy support
            run {
                desugaringClasspathScopes.add(QualifiedContent.Scope.TESTED_CODE)
                desugaringClasspathScopes.add(QualifiedContent.Scope.EXTERNAL_LIBRARIES)
                desugaringClasspathScopes.add(QualifiedContent.Scope.SUB_PROJECTS)
            }
            desugaringClasspathScopes.add(InternalScope.FEATURES)
        } else {
            // legacy Transform API
            @Suppress("DEPRECATION") // Legacy support
            subProjectsClasses =
                transformManager.getPipelineOutputAsFileCollection(
                    { _, scopes -> scopes == setOf(QualifiedContent.Scope.SUB_PROJECTS) },
                    classesFilter
                )
            @Suppress("DEPRECATION") // Legacy support
            externalLibraryClasses =
                transformManager.getPipelineOutputAsFileCollection(
                    { _, scopes -> scopes == setOf(QualifiedContent.Scope.EXTERNAL_LIBRARIES) },
                    classesFilter
                )
            // Get all classes that have more than 1 scope. E.g. project & subproject, or
            // project & subproject & external libs.
            mixedScopeClasses = transformManager.getPipelineOutputAsFileCollection(
                { _, scopes -> scopes.size > 1 && scopes.subtract(TransformManager.SCOPE_FULL_PROJECT).isEmpty() },
                classesFilter
            )
            dexExternalLibsInArtifactTransform =
                creationConfig.services.projectOptions[BooleanOption.ENABLE_DEXING_ARTIFACT_TRANSFORM_FOR_EXTERNAL_LIBS]
        }

        desugaringClasspathForArtifactTransforms = if (dexExternalLibsInArtifactTransform) {
            val testedExternalLibs = (creationConfig as? TestComponentCreationConfig)?.onTestedVariant {
                it.variantDependencies.getArtifactCollection(
                    AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                    AndroidArtifacts.ArtifactScope.ALL,
                    AndroidArtifacts.ArtifactType.CLASSES_JAR
                ).artifactFiles
            } ?: creationConfig.services.fileCollection()

            // Before b/115334911 was fixed, provided classpath did not contain the tested project.
            // Because we do not want tested variant classes in the desugaring classpath for
            // external libraries, we explicitly remove it.
            val testedProject = (creationConfig as? TestComponentCreationConfig)?.onTestedVariant {
                val artifactType =
                    PublishingSpecs.getVariantPublishingSpec(it.componentType).getSpec(
                        AndroidArtifacts.ArtifactType.CLASSES_JAR,
                        AndroidArtifacts.PublishedConfigType.RUNTIME_ELEMENTS
                    )!!.outputType
                creationConfig.services.fileCollection(
                    it.artifacts.get(artifactType)
                )
            } ?: creationConfig.services.fileCollection()

            creationConfig.services.fileCollection(
                creationConfig.transformManager.getPipelineOutputAsFileCollection(
                    { _, scopes ->
                        scopes.subtract(desugaringClasspathScopes).isEmpty()
                    },
                    classesFilter
                ), testedExternalLibs, externalLibraryClasses
            ).minus(testedProject)
        } else {
            creationConfig.services.fileCollection()
        }

        @Suppress("DEPRECATION") // Legacy support
        desugaringClasspathClasses =
            creationConfig.transformManager.getPipelineOutputAsFileCollection(
                { _, scopes ->
                    scopes.contains(QualifiedContent.Scope.TESTED_CODE)
                            || scopes.subtract(desugaringClasspathScopes).isEmpty()
                },
                classesFilter
            )

        @Suppress("DEPRECATION") // Legacy support
        transformManager.consumeStreams(
            TransformManager.SCOPE_FULL_WITH_FEATURES,
            TransformManager.CONTENT_CLASS
        )
    }
}
