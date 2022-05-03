package com.tyron.builder.api.artifacts.type;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Named;
import com.tyron.builder.api.attributes.Attribute;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.attributes.HasAttributes;

import java.util.Set;

/**
 * Meta-data about a particular type of artifacts.
 *
 * @since 4.0
 */
public interface ArtifactTypeDefinition extends HasAttributes, Named {
    /**
     * The attribute that represents the type of the artifact.
     *
     * @since 7.3
     */
    @Incubating
    Attribute<String> ARTIFACT_TYPE_ATTRIBUTE = Attribute.of("artifactType", String.class);

    /**
     * Represents a JAR file.
     *
     * @since 4.0
     */
    String JAR_TYPE = "jar";

    /**
     * Represents a directory tree containing class files.
     *
     * @since 4.0
     */
    String JVM_CLASS_DIRECTORY = "java-classes-directory";

    /**
     * Represents a directory tree containing jvm classpath resource files.
     *
     * @since 4.0
     */
    String JVM_RESOURCES_DIRECTORY = "java-resources-directory";

    /**
     * Represents a zip file
     *
     * @since 5.3
     */
    String ZIP_TYPE = "zip";

    /**
     * Represents a raw directory
     *
     * @since 5.3
     */
    String DIRECTORY_TYPE = "directory";

    /**
     * Represents a binary file
     *
     * @since 7.4
     */
    @Incubating
    String BINARY_DATA_TYPE = "binary";

    /**
     * Returns the set of file name extensions that should be mapped to this artifact type. Defaults to the name of this type.
     */
    Set<String> getFileNameExtensions();

    /**
     * Defines the set of attributes to apply to a component that is packaged as an artifact of this type, when no other attributes are defined. For example, these attributes are applied when a Maven module contains an artifact with one of the extensions listed in {@link #getFileNameExtensions()}.
     */
    @Override
    AttributeContainer getAttributes();
}
