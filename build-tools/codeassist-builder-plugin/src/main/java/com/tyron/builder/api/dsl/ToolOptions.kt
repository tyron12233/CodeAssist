package com.tyron.builder.api.dsl

interface ToolOptions {
    /** If true, run tool workers out of process. */
    var runInSeparateProcess: Boolean

    /**
     * Extra JVM options to give to the out of process worker JVM. Useful for
     * setting things like max memory usage
     */
    val jvmOptions: MutableList<String>

    fun setJvmOptions(from: List<String>)
}