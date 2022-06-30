package com.tyron.builder.api.artifacts;

/**
 * Represents meta-data about a resolved module version.
 */
public interface ResolvedModuleVersion {
    /**
     * The identifier of this resolved module version.
     * @return the identifier
     */
    ModuleVersionIdentifier getId();
}
