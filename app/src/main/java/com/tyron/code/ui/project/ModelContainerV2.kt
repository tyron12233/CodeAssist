package com.tyron.code.ui.project

import com.tyron.builder.model.v2.models.*
import java.io.File
import java.io.Serializable

/**
 * The object returned by the model action via the tooling API.
 *
 * This is meant to contain both the model and the associated sync issue.
 */
class ModelContainerV2(
    val infoMaps: Map<String, Map<String, ModelInfo>>,
    val buildMap: Map<String, BuildInfo>,
) : Serializable {

    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L

        const val ROOT_BUILD_ID = ":"
    }

    data class BuildInfo(
        val name: String,
        val rootDir: File,
        /** projects for this build as pair(projectPath, projectDir) */
        val projects: List<Pair<String, File>>
    ): Serializable {
        companion object {
            @JvmStatic
            private val serialVersionUID: Long = 1L
        }
    }

    data class ModelInfo(
        val projectDir: File,
        val versions: Versions?,
        val basicAndroidProject: BasicAndroidProject?,
        val androidProject: AndroidProject?,
        val androidDsl: AndroidDsl?,
        val variantDependencies: VariantDependencies?,
        val issues: ProjectSyncIssues?
    ): Serializable {
        companion object {
            @JvmStatic
            private val serialVersionUID: Long = 1L
        }

        fun isAndroid(): Boolean {
            return versions != null
        }
    }

    fun getProject(
        projectPath: String? = null,
        buildName: String = ROOT_BUILD_ID
    ): ModelInfo {
        val info: Map<String, ModelInfo> =
            infoMaps[buildName]
                ?: throw RuntimeException("Could not find projects for build '$buildName'")

        return if (projectPath == null) {
            if (info.size != 1) {
                throw RuntimeException("Build '$buildName' contains ${info.size} projects but no path was provided")
            }

            info.values.single()
        } else {
            info[projectPath]
                ?: throw RuntimeException("Failed to find model info for $projectPath in build '$buildName'")
        }
    }

//    /**
//     * Returns the only [NativeModule] when there is no composite builds and a single sub-project
//     * setup for native builds
//     *
//     * (there could be more than one Android sub-project, as long as only one sets up the native
//     * build)
//     */
//    val singleNativeModule: NativeModule
//        get() = infoMaps.values.flatMap { it.values }.mapNotNull { it.nativeModule }.single()

    /**
     * Returns the single [ModelInfo] when there is no composite builds and a single
     * Android sub-project.
     */
    val singleProjectInfo: ModelInfo
        get() {
            if (infoMaps.size != 1) {
                throw RuntimeException("Found ${infoMaps.size} builds when querying for single: ${infoMaps.keys}")
            }
            return rootInfoMap.values.single()
        }

    /**
     * returns the project map for the root build
     */
    val rootInfoMap: Map<String, ModelInfo>
        get() {
            return infoMaps[ROOT_BUILD_ID]
                ?: throw RuntimeException("failed to find project map for root build id: $ROOT_BUILD_ID\nMap = $infoMaps")
        }
}
