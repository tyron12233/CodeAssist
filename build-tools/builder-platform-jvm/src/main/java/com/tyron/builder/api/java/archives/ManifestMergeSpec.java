package com.tyron.builder.api.java.archives;

import groovy.lang.Closure;
import com.tyron.builder.api.Action;

/**
 * Specifies how the entries of multiple manifests should be merged together.
 */
public interface ManifestMergeSpec {

    /**
     * The character set used to decode the merged manifest content.
     * Defaults to UTF-8.
     *
     * @return the character set used to decode the merged manifest content
     * @since 2.14
     */
    String getContentCharset();

    /**
     * The character set used to decode the merged manifest content.
     *
     * @param contentCharset the character set used to decode the merged manifest content
     * @see #getContentCharset()
     * @since 2.14
     */
    void setContentCharset(String contentCharset);

    /**
     * Adds a merge path to a manifest that should be merged into the base manifest. A merge path can be either another
     * {@link com.tyron.builder.api.java.archives.Manifest} or a path that is evaluated as per
     * {@link com.tyron.builder.api.Project#files(Object...)} . If multiple merge paths are specified, the manifest are merged
     * in the order in which they are added.
     *
     * @param mergePaths The paths of manifests to be merged
     * @return this
     */
    ManifestMergeSpec from(Object... mergePaths);

    /**
     * Adds an action to be applied to each key-value tuple in a merge operation. If multiple merge paths are specified,
     * the action is called for each key-value tuple of each merge operation. The given action is called with a
     * {@link com.tyron.builder.api.java.archives.ManifestMergeDetails} as its parameter. Actions are executed
     * in the order added.
     *
     * @param mergeAction A merge action to be executed.
     * @return this
     */
    ManifestMergeSpec eachEntry(Action<? super ManifestMergeDetails> mergeAction);

    /**
     * Adds an action to be applied to each key-value tuple in a merge operation. If multiple merge paths are specified,
     * the action is called for each key-value tuple of each merge operation. The given closure is called with a
     * {@link com.tyron.builder.api.java.archives.ManifestMergeDetails} as its parameter. Actions are executed
     * in the order added.
     *
     * @param mergeAction The action to execute.
     * @return this
     */
    ManifestMergeSpec eachEntry(Closure<?> mergeAction);
}
