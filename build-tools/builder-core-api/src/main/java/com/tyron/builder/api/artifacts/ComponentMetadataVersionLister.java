package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Action;

/**
 * Interface for custom version listers. A version lister is responsible for
 * returning the list of versions of a module which are available in a specific
 * repository. For this, Gradle is going to call the lister once for each module
 * it needs the list of versions. This will typically happen in case a dynamic
 * version is requested, in which case we need to know the list of versions
 * published for this module. It will not, however, be called for fixed version
 * numbers.
 *
 * @since 4.9
 */
public interface ComponentMetadataVersionLister extends Action<ComponentMetadataListerDetails> {
    /**
     * Perform a version listing query
     * @param details the details of the version listing query
     *
     * @since 4.9
     */
    @Override
    void execute(ComponentMetadataListerDetails details);
}
