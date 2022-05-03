package com.tyron.builder.api.attributes;

import com.tyron.builder.api.Named;

/**
 * Attribute representing the technical elements of a library variant.
 *
 * @since 5.6
 */
public interface LibraryElements extends Named {
    Attribute<LibraryElements> LIBRARY_ELEMENTS_ATTRIBUTE = Attribute.of("com.tyron.builder.libraryelements", LibraryElements.class);

    /**
     * The JVM classes format
     */
    String CLASSES = "classes";

    /**
     * The JVM archive format
     */
    String JAR = "jar";

    /**
     * JVM resources
     */
    String RESOURCES = "resources";

    /**
     * The JVM class files and resources
     */
    String CLASSES_AND_RESOURCES = "classes+resources";

    /**
     * Header files for C++
     */
    String HEADERS_CPLUSPLUS = "headers-cplusplus";

    /**
     * Link archives for native modules
     */
    String LINK_ARCHIVE = "link-archive";

    /**
     * Objects for native modules
     */
    String OBJECTS = "objects";

    /**
     * Dynamic libraries for native modules
     */
    String DYNAMIC_LIB = "dynamic-lib";
}
