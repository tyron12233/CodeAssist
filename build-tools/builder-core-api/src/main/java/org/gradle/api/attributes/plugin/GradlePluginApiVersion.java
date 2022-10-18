package org.gradle.api.attributes.plugin;

import org.gradle.api.Incubating;
import org.gradle.api.Named;
import org.gradle.api.attributes.Attribute;

/**
 * Represents a version of the minimal version of the Gradle API required by (variant of) a Gradle plugin.
 *
 * @since 7.0
 */
@Incubating
public interface GradlePluginApiVersion extends Named {

    /**
     * Minimal Gradle version required. See {@link org.gradle.util.GradleVersion} for supported values.
     */
    Attribute<GradlePluginApiVersion> GRADLE_PLUGIN_API_VERSION_ATTRIBUTE = Attribute.of("org.gradle.plugin.api-version", GradlePluginApiVersion.class);
}
