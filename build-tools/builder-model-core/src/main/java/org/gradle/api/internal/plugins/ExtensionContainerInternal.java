package org.gradle.api.internal.plugins;

import org.gradle.api.plugins.ExtensionContainer;

import java.util.Map;

public interface ExtensionContainerInternal extends ExtensionContainer {
    /**
     * Provides access to all known extensions.
     * @return A map of extensions, keyed by name.
     */
    Map<String, Object> getAsMap();
}