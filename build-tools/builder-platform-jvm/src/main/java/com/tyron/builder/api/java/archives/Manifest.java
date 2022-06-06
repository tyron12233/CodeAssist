package com.tyron.builder.api.java.archives;

import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.internal.HasInternalProtocol;

import java.util.Map;

/**
 * Represents the manifest file of a JAR file.
 */
@HasInternalProtocol
public interface Manifest {
    /**
     * Returns the main attributes of the manifest.
     */
    Attributes getAttributes();

    /**
     * Returns the sections of the manifest (excluding the main section).
     *
     * @return A map with the sections, where the key represents the section name and value the section attributes.
     */
    Map<String, Attributes> getSections();

    /**
     * Adds content to the main attributes of the manifest.
     *
     * @param attributes The values to add to the main attributes. The values can be any object. For evaluating the value objects
     * their {@link Object#toString()} method is used. This is done lazily either before writing or when {@link #getEffectiveManifest()}
     * is called.
     *
     * @return this
     * @throws ManifestException If a key is invalid according to the manifest spec or if a key or value is null.
     */
    Manifest attributes(Map<String, ?> attributes) throws ManifestException;

    /**
     * Adds content to the given section of the manifest.
     *
     * @param attributes The values to add to the section. The values can be any object. For evaluating the value objects
     * their {@link Object#toString()} method is used. This is done lazily either before writing or when {@link #getEffectiveManifest()}
     * is called.
     * @param sectionName The name of the section
     *
     * @return this
     * @throws ManifestException If a key is invalid according to the manifest spec or if a key or value is null.
     */
    Manifest attributes(Map<String, ?> attributes, String sectionName) throws ManifestException;

    /**
     * Returns a new manifest instance where all the attribute values are expanded (e.g. their toString method is called).
     * The returned manifest also contains all the attributes of the to be merged manifests specified in {@link #from(Object...)}.
     */
    Manifest getEffectiveManifest();

    /**
     * Writes the manifest into a file. The path's are resolved as defined by {@link com.tyron.builder.api.Project#files(Object...)}
     *
     * The manifest will be encoded using the character set defined by the {@link com.tyron.builder.jvm.tasks.Jar#getManifestContentCharset()} property.
     *
     * @param path The path of the file to write the manifest into.
     * @return this
     */
    Manifest writeTo(Object path);

    /**
     * Specifies other manifests to be merged into this manifest. A merge path can either be another instance of
     * {@link com.tyron.builder.api.java.archives.Manifest} or a file path as interpreted by {@link com.tyron.builder.api.Project#files(Object...)}.
     *
     * The merge is not happening instantaneously. It happens either before writing or when {@link #getEffectiveManifest()}
     * is called.
     *
     * @return this
     */
    Manifest from(Object... mergePath);

    /**
     * Specifies other manifests to be merged into this manifest. A merge path is interpreted as described in
     * {@link #from(Object...)}.
     *
     * The merge is not happening instantaneously. It happens either before writing or when {@link #getEffectiveManifest()}
     * is called.
     *
     * The closure configures the underlying {@link com.tyron.builder.api.java.archives.ManifestMergeSpec}.
     *
     * @return this
     */
    Manifest from(Object mergePath, Closure<?> closure);

    /**
     * Specifies other manifests to be merged into this manifest. A merge path is interpreted as described in
     * {@link #from(Object...)}.
     *
     * The merge is not happening instantaneously. It happens either before writing or when {@link #getEffectiveManifest()}
     * is called.
     *
     * @return this
     * @since 5.0
     */
    Manifest from(Object mergePath, Action<ManifestMergeSpec> action);
}
