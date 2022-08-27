package com.tyron.builder.api.dsl

import org.gradle.api.Incubating
import java.io.File

/**
 * DSL object for configuring postProcessing: removing dead code, obfuscating etc.
 *
 * <p>This DSL is incubating and subject to change. To configure code and resource shrinkers,
 * Instead use the properties already available in the <a
 * href="com.android.build.gradle.internal.dsl.BuildType.html"><code>buildType</code></a> block.
 *
 * <p>To learn more, read <a
 * href="https://developer.android.com/studio/build/shrink-code.html">Shrink Your Code and
 * Resources</a>.
 */
@Incubating
interface PostProcessing {
    fun initWith(that: PostProcessing)

    var isRemoveUnusedCode: Boolean

    var isRemoveUnusedResources: Boolean

    var isObfuscate: Boolean

    var isOptimizeCode: Boolean

    fun setProguardFiles(proguardFiles: List<Any>)
    fun proguardFile(file: Any)
    fun proguardFiles(vararg files: Any)

    fun setTestProguardFiles(testProguardFiles: List<Any>)
    fun testProguardFile(file: Any)
    fun testProguardFiles(vararg files: Any)

    fun setConsumerProguardFiles(consumerProguardFiles: List<Any>)
    fun consumerProguardFile(file: Any)
    fun consumerProguardFiles(vararg files: Any)

    @Deprecated("This property no longer has any effect. R8 is always used.")
    var codeShrinker: String
}