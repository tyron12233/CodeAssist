package com.tyron.builder.api.artifacts.component;

import javax.annotation.Nullable;

/**
 * Criteria for selecting a library instance that is built as part of the current build.
 */
public interface LibraryComponentSelector extends ComponentSelector {
    /**
     * Return the project path of the selected library.
     *
     * @return the project path of the library
     */
    String getProjectPath();

    /**
     * Return the library name of the selected library.
     * If the library name is null then it is expected to find a single library defined in same project
     * as the requesting component or dependency resolution will fail.
     * If not <code>null</code> then the name will never be empty.
     *
     * @return the library name
     */
    @Nullable
    String getLibraryName();

    @Nullable
    String getVariant();

}
