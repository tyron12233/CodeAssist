package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * DSL object for external library dependencies keep rules configurations.
 */
@Incubating
interface KeepRules {

    /**
     * Ignore keep rules from listed external dependencies. External dependencies can be specified
     * via GAV coordinates(e.g. "groupId:artifactId:version") or in the format of
     * "groupId:artifactId" in which case dependencies are ignored as long as they match
     * groupId & artifactId.
     */
    @Incubating
    fun ignoreExternalDependencies(vararg ids: String)

    /**
     * Ignore keep rules from all the external dependencies.
     */
    @Incubating
    fun ignoreAllExternalDependencies(ignore: Boolean)
}