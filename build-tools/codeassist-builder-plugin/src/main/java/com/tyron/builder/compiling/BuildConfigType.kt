package com.tyron.builder.compiling

/**
 * BuildConfigType values describe the existence & format of a BuildConfig class.
 */
enum class BuildConfigType {
    // BuildConfig is represented by a Jar file containing a compiled BuildConfig .class file.
    JAR,
    // BuildConfig is represented by a Java source file.
    JAVA_SOURCE
}