package com.tyron.builder.model.level2;

import com.android.annotations.NonNull;
import com.tyron.builder.model.AndroidProject;
import java.util.Map;

/**
 * Global map of all the {@link Library} instances used in a single or multi-module gradle project.
 *
 * This is a separate model to query (the same way {@link AndroidProject} is queried). It must
 * be queried after all the models have been queried for their {@link AndroidProject}.
 *
 * @since 2.3
 */
public interface GlobalLibraryMap {

    /**
     * the list of external libraries used by all the variants in the module.
     *
     * @return the map of address to library
     */
    @NonNull
    Map<String, Library> getLibraries();
}
