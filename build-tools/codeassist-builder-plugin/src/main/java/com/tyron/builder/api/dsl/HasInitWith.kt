package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * For build types and product flavors, they can be initialized from the current state of another.
 *
 * This can be useful to save repeating configuration in the build file when two build types
 * or flavors should be almost identical.
 *
 * Custom extensions attached to build type and product flavor should implement this,
 * so that they can also be initialized when Android Gradle plugin initialized the build type or
 * product flavor too.
 * Note that this will only work with Android Gradle Plugin 7.1 or higher, for older versions
 * this will have no effect.
 */
@Incubating
interface HasInitWith<T: Any> {
    /** Copy the properties of the given build type, product flavor or custom extension to this */
    fun initWith(that: T)
}
