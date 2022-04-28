package com.tyron.builder.internal.scopeids.id;


import com.tyron.builder.internal.id.UniqueId;

/**
 * The persistent ID of a potential build on disk.
 *
 * It is effectively the root dir of a build.
 * That is, two builds with the same root dir share the same workspace.
 *
 * In practice, this generally maps to what users would think of as “checkout” of a project.
 * Builds of the same checkout over time will share the same workspace ID.
 *
 * This ID is persisted in the root build's project cache dir.
 * If this cache directory is destroyed, a new ID will be issued.
 */
public final class WorkspaceScopeId extends ScopeId {

    public WorkspaceScopeId(UniqueId id) {
        super(id);
    }

}