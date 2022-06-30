package com.tyron.builder.api.internal.classloading;

public interface GroovySystemLoader {
    /**
     * Invoked when this Groovy system is to be discarded, so that the Groovy system can remove any static state it may have registered in other ClassLoaders.
     */
    void shutdown();

    /**
     * Invoked when another ClassLoader is discarded, so that this Groovy system can remove state for the classes loaded from the ClassLoader
     */
    void discardTypesFrom(ClassLoader classLoader);
}
