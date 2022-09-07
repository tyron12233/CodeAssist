package com.tyron.builder.gradle.internal.ide.dependencies

import com.android.SdkConstants.EXT_AAR
import com.android.SdkConstants.EXT_JAR
import com.tyron.builder.dependency.MavenCoordinatesImpl
import com.tyron.builder.internal.StringCachingService
import com.tyron.builder.model.MavenCoordinates
import com.google.common.collect.ImmutableMap
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.internal.component.local.model.OpaqueComponentArtifactIdentifier
import java.io.File
import java.util.regex.Pattern

/**
 * Artifact information relevant about the computation of the dependency model sent to the IDE.
 *
 * This is generally computed from a [ResolvedArtifactResult] (which is not usable as a [Map]
 * key) plus additional information.
 */
data class ResolvedArtifact internal constructor(
    val componentIdentifier: ComponentIdentifier,
    val variant: ResolvedVariantResult,
    val variantName: String?,
    val artifactFile: File?,
    val isTestFixturesArtifact: Boolean,
    /**
     * An optional sub-result that represents the bundle file, when the current result
     * represents an exploded aar
     */
    val extractedFolder: File?,
    /** optional published lint jar */
    val publishedLintJar: File?,
    val dependencyType: DependencyType,
    val isWrappedModule: Boolean,
    val buildMapping: ImmutableMap<String, String>,
)  {

    constructor(
        mainArtifactResult: ResolvedArtifactResult,
        extractedFolder: File?,
        publishedLintJar: File?,
        dependencyType: DependencyType,
        isWrappedModule: Boolean,
        buildMapping: ImmutableMap<String, String>,
    ) :
            this(
                mainArtifactResult.id.componentIdentifier,
                mainArtifactResult.variant,
                mainArtifactResult.getVariantName(),
                mainArtifactResult.file,
                mainArtifactResult.hasProjectTestFixturesCapability() ||
                        mainArtifactResult.hasLibraryTestFixturesCapability(),
                extractedFolder,
                publishedLintJar,
                dependencyType,
                isWrappedModule,
                buildMapping,
            )

    enum class DependencyType constructor(val extension: String) {
        JAVA(EXT_JAR),
        ANDROID(EXT_AAR),
        RELOCATED_ARTIFACT(""),
        // An artifact without file, but it may contain dependencies.
        NO_ARTIFACT_FILE(""),
    }

    /**
     * Computes Maven Coordinate for a given artifact result.
     */
    fun computeMavenCoordinates(
        stringCachingService: StringCachingService
    ): MavenCoordinates {
        return when (componentIdentifier) {
            is ModuleComponentIdentifier -> {
                val module = componentIdentifier.module
                val version = componentIdentifier.version
                val extension = dependencyType.extension
                var classifier: String? = null

                if (!artifactFile!!.isDirectory) {
                    // attempts to compute classifier based on the filename.
                    val pattern = "^$module-$version-(.+)\\.$extension$"

                    val p = Pattern.compile(pattern)
                    val m = p.matcher(artifactFile!!.name)
                    if (m.matches()) {
                        classifier = m.group(1)
                    }
                }

                MavenCoordinatesImpl.create(
                    stringCachingService = stringCachingService,
                    groupId = componentIdentifier.group,
                    artifactId = module,
                    version = version,
                    packaging = extension,
                    classifier = classifier
                )
            }

            is ProjectComponentIdentifier -> {
                MavenCoordinatesImpl.create(
                    stringCachingService = stringCachingService,
                    groupId = "artifacts",
                    artifactId = componentIdentifier.getIdString(),
                    version = "unspecified"
                )
            }

            is OpaqueComponentArtifactIdentifier -> {
                // We have a file based dependency
                if (dependencyType == DependencyType.JAVA) {
                    MavenCoordinatesCacheBuildService.getMavenCoordForLocalFile(
                        artifactFile!!,
                        stringCachingService
                    )
                } else {
                    // local aar?
                    assert(artifactFile!!.isDirectory)
                    MavenCoordinatesCacheBuildService.getMavenCoordForLocalFile(
                        artifactFile!!,
                        stringCachingService
                    )
                }
            }

            else -> {
                throw RuntimeException(
                    "Don't know how to compute maven coordinate for artifact '"
                            + componentIdentifier.displayName
                            + "' with component identifier of type '"
                            + componentIdentifier.javaClass
                            + "'."
                )
            }
        }
    }

    /**
     * Computes a unique address to use in the level 4 model
     */
    fun computeModelAddress(
        mavenCoordinatesCache: MavenCoordinatesCacheBuildService
    ): String = when (componentIdentifier) {
        is ProjectComponentIdentifier -> {

            StringBuilder(100)
                .append(componentIdentifier.getBuildId(buildMapping))
                .append("@@")
                .append(componentIdentifier.projectPath)
                .also { sb ->
                    this.variantName?.let{ sb.append("::").append(it) }
                    if (this.isTestFixturesArtifact) {
                        sb.append("::").append("testFixtures")
                    }
                }
                .toString().intern()
        }
        is ModuleComponentIdentifier, is OpaqueComponentArtifactIdentifier -> {
            (mavenCoordinatesCache.getMavenCoordinates(this).toString() +
                    if (this.isTestFixturesArtifact) {
                        "::testFixtures"
                    } else "").intern()
        }
        else -> {
            throw RuntimeException(
                "Don't know how to handle ComponentIdentifier '"
                        + componentIdentifier.displayName
                        + "'of type "
                        + componentIdentifier.javaClass)
        }
    }
}
