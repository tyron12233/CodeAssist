package org.gradle.plugins.ide.idea.model;

import groovy.util.Node;

/**
 * Represents a dependency of an IDEA module.
 */
public interface Dependency {

    /**
     * The scope of this library. If <code>null</code>, the scope attribute is not added.
     * @since 4.5
     */
    String getScope();

    /**
     * The scope of this library. If <code>null</code>, the scope attribute is not added.
     * @since 4.5
     */
    void setScope(String scope);

    void addToNode(Node parentNode);
}