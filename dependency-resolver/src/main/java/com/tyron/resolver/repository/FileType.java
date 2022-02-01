package com.tyron.resolver.repository;

public enum FileType {

    /**
     * A jar file containing compiled class files
     */
    JAR("jar"),

    /**
     * Android specific package, similar to a JAR but with resources
     */
    AAR("aar"),

    /**
     * A jar file containing sources for comments and inspection
     */
    SOURCE_JAR("jar"),

    /**
     * The pom file containing the library information and its dependencies
     */
    POM("pom");


    private final String mExtension;

    FileType(String extension) {
        mExtension = extension;
    }


    String getExtension() {
        return mExtension;
    }
}
