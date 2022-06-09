package com.tyron.builder.initialization;

public interface JdkToolsInitializer {
    /**
     * Ensures that the JDK tools are visible on the system ClassLoader. Not really a great idea, but here for backwards
     * compatibility.
     */
    void initializeJdkTools();
}
