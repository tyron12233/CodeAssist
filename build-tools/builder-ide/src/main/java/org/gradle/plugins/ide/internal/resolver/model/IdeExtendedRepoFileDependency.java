package org.gradle.plugins.ide.internal.resolver.model;

import org.gradle.api.artifacts.ModuleVersionIdentifier;

/**
 * TODO only here because Kotlin DSL uses this. Please remove once that is fixed.
 */
public class IdeExtendedRepoFileDependency {

    private ModuleVersionIdentifier id;

    public ModuleVersionIdentifier getId() {
        return id;
    }

    public void setId(ModuleVersionIdentifier id) {
        this.id = id;
    }
}
