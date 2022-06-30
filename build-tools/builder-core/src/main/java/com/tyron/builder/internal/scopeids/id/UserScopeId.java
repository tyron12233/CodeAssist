package com.tyron.builder.internal.scopeids.id;

import com.tyron.builder.internal.id.UniqueId;

/**
 * A persistent ID of a user.
 *
 * It is effectively the Gradle user home dir.
 * That is, two builds by the same operating system user, potentially of different “projects”,
 * share the same user ID.
 *
 * This ID is persisted in the Gradle user home dir.
 * If this directory is destroyed, or a build is run with a different gradle user home,
 * a new ID will be issued.
 */
public final class UserScopeId extends ScopeId {

    public UserScopeId(UniqueId id) {
        super(id);
    }

}