package com.tyron.builder.api.dsl

/**
 * Maven publishing options shared by [SingleVariant] and [MultipleVariants].
 *
 * To publish sources & javadoc jar apart from AAR, use [withSourcesJar] and [withJavadocJar].
 * The following sets up publishing of sources & javadoc jar in two different publishing mechanisms.
 *
 * ```
 * android {
 *     publishing {
 *         singleVariant("release") {
 *             withSourcesJar()
 *             withJavadocJar()
 *         }
 *
 *         multipleVariants {
 *             withSourcesJar()
 *             withJavadocJar()
 *             allVariants()
 *         }
 *     }
 * }
 * ```
 */
interface PublishingOptions {

    /**
     * Publish java & kotlin sources jar as a secondary artifact to a Maven repository.
     */
    fun withSourcesJar()

    /**
     * Publish javadoc jar generated from java & kotlin source as a secondary artifact to a Maven
     * repository.
     */
    fun withJavadocJar()
}