package com.tyron.builder.api.artifacts.dsl;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.ComponentModuleMetadataDetails;

/**
 * Allows to modify the metadata of depended-on software components.
 *
 * <p> Example:
 * <pre class='autoTested'>
 * dependencies {
 *     modules {
 *         //Configuring component module metadata for the entire "google-collections" module,
 *         // declaring that legacy library was replaced with "guava".
 *         //This way, Gradle's conflict resolution can use this information and use "guava"
 *         // in case both libraries appear in the same dependency tree.
 *         module("com.google.collections:google-collections") {
 *             replacedBy("com.google.guava:guava")
 *         }
 *     }
 * }
 * </pre>
 *
 * @since 2.2
 */
public interface ComponentModuleMetadataHandler {
    /**
     * Enables configuring component module metadata.
     * This metadata applies to the entire component module (e.g. "group:name", like "com.tyron.builder:gradle-core") regardless of the component version.
     *
     * <pre class='autoTested'>
     * //declaring that google collections are replaced by guava
     * //so that conflict resolution can take advantage of this information:
     * dependencies.modules.module('com.google.collections:google-collections') { replacedBy('com.google.guava:guava') }
     * </pre>
     *
     * @param moduleNotation an identifier of the module. String "group:name", e.g. 'com.tyron.builder:gradle-core'
     * or an instance of {@link com.tyron.builder.api.artifacts.ModuleIdentifier}
     * @param rule a rule that applies to the components of the specified module
     * @since 2.2
     */
    void module(Object moduleNotation, Action<? super ComponentModuleMetadataDetails> rule);
}
