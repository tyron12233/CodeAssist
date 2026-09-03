package dev.ide.android.support.tasks

import dev.ide.android.support.crashlytics.Crashlytics
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import java.nio.file.Files
import java.nio.file.Path

/**
 * `inject<Variant>CrashlyticsMappingFileId`: write the `com.google.firebase.crashlytics.mapping_file_id`
 * string resource into [outResDir], which the resource merge then picks up. The on-device counterpart of
 * the Crashlytics Gradle plugin's `InjectMappingFileIdTask`; runs only when `firebase-crashlytics` is on
 * the app's classpath, because without this resource the Crashlytics runtime throws at startup (see
 * [Crashlytics]).
 */
internal class InjectCrashlyticsMappingFileIdTask(
    override val name: TaskName,
    private val outResDir: Path,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply { property("mappingFileId", Crashlytics.BLANK_MAPPING_FILE_ID) }

    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("res", outResDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val values = outResDir.resolve("values").resolve(Crashlytics.RESOURCE_FILE_NAME)
        Files.createDirectories(values.parent)
        Files.write(values, Crashlytics.mappingFileIdXml().toByteArray(Charsets.UTF_8))
        ctx.logger()("injectCrashlyticsMappingFileId -> ${Crashlytics.MAPPING_FILE_ID_RESOURCE}")
        return TaskResult.Success
    }
}
