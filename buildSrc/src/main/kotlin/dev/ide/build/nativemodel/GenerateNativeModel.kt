package dev.ide.build.nativemodel

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Exports this Gradle build as the dump CodeAssist's own project model is bootstrapped from, so the IDE can
 * build the IDE. See [NativeModelExtractor] for what the dump holds and why it is the declared model rather
 * than a resolved graph.
 *
 * ```
 * ./gradlew generateNativeModel                          # android target, the APK's shape
 * ./gradlew generateNativeModel -PnativeModel.target=desktop
 * ```
 *
 * The task reads the configured model of every subproject, so it deliberately has no up-to-date check worth
 * having (any build script edit changes its result and Gradle re-evaluates the scripts anyway); it is cheap
 * and always runs.
 */
abstract class GenerateNativeModel : DefaultTask() {

    @get:Input
    abstract val target: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        group = "build setup"
        description = "Exports the Gradle model as .platform/gradle-model.json for CodeAssist's own build system."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun generate() {
        val json = NativeModelExtractor.extract(project.rootProject, target.get())
        val out = outputFile.get().asFile
        out.parentFile?.mkdirs()
        out.writeText(json)
        val modules = Regex("\"name\"").findAll(json).count()
        logger.lifecycle("native model -> ${out.path} (target=${target.get()}, $modules entries)")
    }
}
