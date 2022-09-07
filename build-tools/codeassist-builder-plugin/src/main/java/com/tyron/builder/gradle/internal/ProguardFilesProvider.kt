package com.tyron.builder.gradle.internal

import java.io.File

/**
 * An interface to unify access to different proguard file types
 */
interface ProguardFilesProvider {
    /**
     * @param type - a type of proguard files to be returned
     * @returns unresolved collection of proguard files
     */
    fun getProguardFiles(type: ProguardFileType): Collection<File>
}

/**
 * Types of proguard files
 */
enum class ProguardFileType {
    /**
     * Files specified by proguardFile(s) in DSL
     */
    EXPLICIT,
    /**
     * Files specified by testProguardFile(s) in DSL
     */
    TEST,
    /**
     * Files specified by consumerProguardFile(s) in DSL
     */
    CONSUMER
}