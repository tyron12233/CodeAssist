package com.tyron.builder.gradle.internal.tasks

import com.google.common.collect.Maps
import org.gradle.api.provider.MapProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import javax.inject.Inject

/**
 * A class that checks for duplicate classes within an ArtifactCollection. Classes are assumed to be
 * duplicate if they have the same name and they are positioned within the same package (this is
 * possible if they are in different artifacts).
 */
class CheckDuplicateClassesDelegate {
    private fun extractClasses(artifactMap: Map<String, File>): Map<String, List<String>> {
        val classesMap = HashMap<String, List<String>>()

        artifactMap.keys.forEach { key: String ->
            val artifactFile = artifactMap.getValue(key)
            if (artifactFile.exists()) {
                classesMap[key] = artifactFile.readLines()
            }
        }
        return classesMap
    }

    fun run(
        enumeratedClasses: Map<String, File>) {

        val classesMap = extractClasses(enumeratedClasses)

        val maxSize = classesMap.map { it.value.size }.sum()
        val classes = Maps.newHashMapWithExpectedSize<String, MutableList<String>>(maxSize)

        classesMap.forEach {
            val artifactName = it.key
            it.value.forEach { className ->
                classes.getOrPut(className) { mutableListOf() }.add(artifactName)
            }
        }

        val duplicatesMap = classes.filter { it.value.size > 1 }.toSortedMap()
        if (!duplicatesMap.isEmpty()) {
            val lineSeparator = System.lineSeparator()
            val duplicateMessages = duplicatesMap
                .map { duplicateClassMessage(it.key, it.value) }
                .joinToString(lineSeparator)
            throw RuntimeException("$duplicateMessages$lineSeparator$lineSeparator$RECOMMENDATION")
        }
    }
}

private const val RECOMMENDATION =
    "Go to the documentation to learn how to <a href=\"d.android.com/r/tools/classpath-sync-errors\">Fix dependency resolution errors</a>."

private fun duplicateClassMessage(className: String, artifactNames: List<String>): String {
    val sorted = artifactNames.sorted()
    val modules = when {
        artifactNames.size == 2 -> "modules ${sorted[0]} and ${sorted[1]}"
        else -> {
            val last = sorted.last()
            "the following modules: ${sorted.dropLast(1).joinToString(", ")} and $last"
        }
    }
    return "Duplicate class $className found in $modules"
}

abstract class CheckDuplicatesParams: WorkParameters {
    abstract val enumeratedClasses: MapProperty<String, File>
}

abstract class CheckDuplicatesRunnable @Inject constructor(): WorkAction<CheckDuplicatesParams> {
    override fun execute() {
        CheckDuplicateClassesDelegate().run(
            parameters.enumeratedClasses.get()
        )
    }
}
