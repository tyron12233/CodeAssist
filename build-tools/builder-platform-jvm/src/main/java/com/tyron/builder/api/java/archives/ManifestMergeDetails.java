package com.tyron.builder.api.java.archives;

/**
 * Details of a value being merged from two different manifests.
 */
public interface ManifestMergeDetails {
    /**
     * Returns the section this merge details belongs to.
     */
    String getSection();

    /**
     * Returns the key that is to be merged.
     */
    String getKey();

    /**
     * Returns the value for the key of the manifest that is the target of the merge.
     */
    String getBaseValue();

    /**
     * Returns the value for the key of the manifest that is the source for the merge.
     */
    String getMergeValue();

    /**
     * Returns the value for the key of the manifest after the merge takes place. By default this is the value
     * of the source for the merge.
     */
    String getValue();

    /**
     * Set's the value for the key of the manifest after the merge takes place.
     */
    void setValue(String value);

    /**
     * Excludes this key from being in the manifest after the merge.
     */
    void exclude();
}
