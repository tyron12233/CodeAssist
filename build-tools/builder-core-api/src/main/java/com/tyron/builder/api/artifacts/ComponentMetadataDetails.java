package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.NonExtensible;
import com.tyron.builder.api.attributes.HasConfigurableAttributes;

import java.util.List;

/**
 * Describes a resolved component's metadata, which typically originates from
 * a component descriptor (Ivy file, Maven POM). Some parts of the metadata can be changed
 * via metadata rules (see {@link com.tyron.builder.api.artifacts.dsl.ComponentMetadataHandler}.
 *
 * @since 1.8
 */
@NonExtensible
public interface ComponentMetadataDetails extends ComponentMetadata, HasConfigurableAttributes<ComponentMetadataDetails> {
    /**
     * Sets whether the component is changing or immutable.
     *
     * @param changing whether the component is changing or immutable
     */
    void setChanging(boolean changing);

    /**
     * Sets the status of the component. Must
     * match one of the values in {@link #getStatusScheme()}.
     *
     * @param status the status of the component
     */
    void setStatus(String status);

    /**
     * Sets the status scheme of the component. Values are ordered
     * from least to most mature status.
     *
     * @param statusScheme the status scheme of the component
     */
    void setStatusScheme(List<String> statusScheme);

    /**
     * Add a rule for adjusting an existing variant of the component.
     *
     * @param name name of the variant to adjust (e.g. 'compile')
     * @param action the action to modify the variant
     *
     * @since 4.4
     */
    void withVariant(String name, Action<? super VariantMetadata> action);

    /**
     * Add a rule for adjusting all variants of a component.
     *
     * @param action the action to be executed on each variant.
     *
     * @since 4.5
     */
    void allVariants(Action<? super VariantMetadata> action);

    /**
     * Add a rule for adding a new empty variant to the component.
     *
     * @param name a name for the variant
     * @param action the action to populate the variant
     *
     * @since 6.0
     */
    void addVariant(String name, Action<? super VariantMetadata> action);

    /**
     * Add a rule for adding a new variant to the component. The new variant will be based on an existing variant
     * or configurations of the component and initialized with the same attributes, capabilities, dependencies and artifacts.
     * These can then be modified in the given configuration action.
     * Whether the 'base' is already a variant (with attributes) or a plain configuration (without attributes) depends on the
     * metadata source:
     *
     * <ul>
     *     <li>Gradle Module Metadata: all variants defined in the metadata are available as base</li>
     *     <li>POM Metadata: the 'compile' and 'runtime' variants with the Java ecosystem attributes are available as base</li>
     *     <li>Ivy Metadata: all configurations defined in the metadata are available as base</li>
     * </ul>
     *
     * Note: files (artifacts) are not initialized automatically and always need to be added through {@link VariantMetadata#withFiles(Action)}.
     *
     * @param name a name for the variant
     * @param base name of the variant (pom or Gradle module metadata) or configuration (ivy.xml metadata) from which the new variant will be initialized
     * @param action the action to populate the variant
     *
     * @since 6.0
     */
    void addVariant(String name, String base, Action<? super VariantMetadata> action);

    /**
     * This is the lenient version of {@link #addVariant(String, String, Action)}.
     * The only difference is that this will do nothing (instead of throwing an error), if the 'base' variant does not exist for a component.
     * This is particularly useful for rules that are applied to {@link com.tyron.builder.api.artifacts.dsl.ComponentMetadataHandler#all(Class)} components.
     *
     * @param name a name for the variant
     * @param base name of the variant (pom or Gradle module metadata) or configuration (ivy.xml metadata) from which the new variant will be initialized
     * @param action the action to populate the variant
     *
     * @since 6.1
     */
    void maybeAddVariant(String name, String base, Action<? super VariantMetadata> action);

    /**
     * Declares that this component belongs to a virtual platform, which should be
     * considered during dependency resolution.
     * @param notation the coordinates of the owner
     *
     * @since 4.10
     */
    void belongsTo(Object notation);

    /**
     * Declares that this component belongs to a platform, which should be
     * considered during dependency resolution.
     * @param notation the coordinates of the owner
     * @param virtual must be set to true if the platform is a virtual platform, or false if it's a published platform
     *
     * @since 5.0
     */
    void belongsTo(Object notation, boolean virtual);
}
