package com.tyron.builder.gradle.internal.dependency

import com.android.SdkConstants
import com.android.SdkConstants.COM_ANDROID_TOOLS_FOLDER
import com.android.ide.common.repository.GradleVersion
import com.android.utils.FileUtils
import com.google.common.annotations.VisibleForTesting
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.Locale

@DisableCachingByDefault
abstract class FilterShrinkerRulesTransform :
    TransformAction<FilterShrinkerRulesTransform.Parameters> {
    interface Parameters : GenericTransformParameters {
        @get:Input
        val shrinker: Property<VersionedCodeShrinker>
    }

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(transformOutputs: TransformOutputs) {
        //TODO(b/162813654) record transform execution span
        val input = inputArtifact.get().asFile
        if (input.isFile) {
            // if input is a regular file, it is simply always accepted, no need to filter
            transformOutputs.file(input.absolutePath)
        } else if (input.isDirectory) {
            // this will handle inputs that look like this:
            // input/
            // ├── lib0/
            // |   ├── proguard.txt (optional, coming from AAR)
            // │   └── META-INF/
            // |       ├── proguard/ (optional, coming from JAR)
            // │       └── com.android.tools/ (optional, coming from JAR)
            // │           ├── r8[...][...]
            // │           └── proguard[...][...]
            // ├── lib1/
            // │   └── ...
            // ...

            // loop through top-level directories and join the results into a list
            input.listFiles { file -> file.isDirectory }.flatMap { libDir ->
                // if there is a com.android.tools directory, it takes precedence over legacy rules
                val toolsDir = FileUtils.join(libDir, "META-INF", COM_ANDROID_TOOLS_FOLDER)
                if (toolsDir.isDirectory) {
                    // gather all directories under com.android.tools that match shrinker version...
                    return@flatMap toolsDir.listFiles { file ->
                        file.isDirectory && configDirMatchesVersion(
                            file.name,
                            parameters.shrinker.get()
                        )
                    }.flatMap { shrinkerConfigDir ->
                        // ...then gather all regular files under the matching directories
                        shrinkerConfigDir.listFiles { file -> file.isFile }.asIterable()
                    }
                } else {
                    // there will be either a libDir/proguard.txt file or libDir/META-INF/proguard/*
                    // order doesn't really matter, as there never should be both
                    val proguardTxtFile = File(libDir, SdkConstants.FN_PROGUARD_TXT)
                    if (proguardTxtFile.isFile) {
                        return@flatMap listOf(proguardTxtFile)
                    } else {
                        val proguardConfigDir = FileUtils.join(libDir, "META-INF", "proguard")
                        if (proguardConfigDir.isDirectory) {
                            // gets all files from the META-INF/proguard/ directory
                            return@flatMap proguardConfigDir
                                .listFiles { file -> file.isFile }
                                .asIterable()
                        }
                    }
                }
                emptyList<File>()
            }.forEach { file ->
                transformOutputs.file(file.absolutePath)
            }
        }
    }
}

// Regex for directories containing PG/R8 configuration files inside META-INF/com.android.tools/
private val configDirRegex = """r8(?:-from-([^:@]+?))?(?:-upto-([^:@]+?))?""".toRegex()

@VisibleForTesting
internal fun configDirMatchesVersion(
    dirName: String,
    versionedShrinker: VersionedCodeShrinker
): Boolean {
    configDirRegex.matchEntire(dirName.lowercase(Locale.US))?.let { matchResult ->
        val (from, upto) = matchResult.destructured

        if (from.isEmpty() && upto.isEmpty()) {
            return true
        }

        val shrinkerCoord =
            GradleVersion.tryParse(versionedShrinker.version) ?: return false
        if (from.isNotEmpty()) {
            val minCoord = GradleVersion.tryParse(from) ?: return false
            if (minCoord.compareTo(shrinkerCoord) > 0) {
                return false
            }
        }
        if (upto.isNotEmpty()) {
            val maxCoord = GradleVersion.tryParse(upto) ?: return false
            if (maxCoord.compareTo(shrinkerCoord) <= 0) {
                return false
            }
        }
        return true
    } ?: return false
}
