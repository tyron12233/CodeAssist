package com.tyron.builder.gradle.internal.packaging

import com.tyron.builder.packaging.JarCreator
import com.tyron.builder.packaging.JarFlinger
import com.tyron.builder.packaging.JarMerger
import java.nio.file.Path
import java.util.function.Predicate

object JarCreatorFactory {

    fun make(
        jarFile: Path,
        filter: Predicate<String>? = null,
        type: JarCreatorType = JarCreatorType.JAR_FLINGER
    ): JarCreator {
        return when (type) {
            JarCreatorType.JAR_MERGER -> JarMerger(jarFile, filter)
            JarCreatorType.JAR_FLINGER -> JarFlinger(jarFile, filter)
        }
    }
}

enum class JarCreatorType {
    JAR_MERGER,
    JAR_FLINGER,
}