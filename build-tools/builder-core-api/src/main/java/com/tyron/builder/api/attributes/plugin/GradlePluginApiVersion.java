package com.tyron.builder.api.attributes.plugin;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Named;
import com.tyron.builder.api.attributes.Attribute;

/**
 * Represents a version of the minimal version of the Gradle API required by (variant of) a Gradle plugin.
 *
 * @since 7.0
 */
@Incubating
public interface GradlePluginApiVersion extends Named {

    /**
     * Minimal Gradle version required. See {@link com.tyron.builder.util.GradleVersion} for supported values.
     */
    Attribute<GradlePluginApiVersion> GRADLE_PLUGIN_API_VERSION_ATTRIBUTE = Attribute.of("com.tyron.builder.plugin.api-version", GradlePluginApiVersion.class);
}
