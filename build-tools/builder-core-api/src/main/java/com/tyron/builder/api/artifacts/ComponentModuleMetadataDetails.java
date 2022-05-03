package com.tyron.builder.api.artifacts;

import javax.annotation.Nullable;

/**
 * Contains and allows configuring component module metadata information.
 * For information and examples please see {@link com.tyron.builder.api.artifacts.dsl.ComponentModuleMetadataHandler}
 *
 * @since 2.2
 */
public interface ComponentModuleMetadataDetails extends ComponentModuleMetadata {

    /**
     * Configures a replacement module for this module.
     * A real world example: 'com.google.collections:google-collections' is replaced by 'com.google.guava:guava'.
     *
     * Subsequent invocations of this method replace the previous 'replacedBy' value.
     *
     * For information and examples please see {@link com.tyron.builder.api.artifacts.dsl.ComponentMetadataHandler}.
     *
     * @param moduleNotation a String like 'com.google.guava:guava', an instance of {@link com.tyron.builder.api.artifacts.ModuleVersionIdentifier}, null is not permitted
     */
    void replacedBy(Object moduleNotation);

    /**
     * Configures a replacement module for this module and provides an explanation for the replacement.
     *
     * A real world example: 'com.google.collections:google-collections' is replaced by 'com.google.guava:guava'.
     *
     * Subsequent invocations of this method replace the previous 'replacedBy' value.
     *
     * For information and examples please see {@link com.tyron.builder.api.artifacts.dsl.ComponentMetadataHandler}.
     *
     * @param moduleNotation a String like 'com.google.guava:guava', an instance of {@link com.tyron.builder.api.artifacts.ModuleVersionIdentifier}, null is not permitted
     * @param reason the reason for the replacement, for diagnostics
     *
     * @since 4.5
     */
    void replacedBy(Object moduleNotation, @Nullable String reason);
}
