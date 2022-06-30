package com.tyron.builder.api.artifacts.component;

/**
 * An identifier for a library instance that is built as part of the current build.
 */
public interface LibraryBinaryIdentifier extends ComponentIdentifier {

    /**
     * The project path of the library.
     * @return The project path of the library.
     */
    String getProjectPath();

    /**
     * The name of the library
     * @return the name of the library
     */
    String getLibraryName();

    /**
     * The variant of the library.
     * @return the variant identifier of the library.
     */
    String getVariant();
}
