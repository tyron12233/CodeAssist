package com.tyron.builder.internal.scopeids.id;

import com.tyron.builder.internal.id.UniqueId;

/**
 * The ID of a single build invocation.
 *
 * Here, the term "build" is used to represent the overall invocation.
 * For example, buildSrc shares the same build scope ID as the overall build.
 * All composite participants also share the same build scope ID.
 * That is, all “nested” builds (in terms of GradleLauncher etc.) share the same build ID.
 *
 * This ID is, by definition, not persistent.
 */
public final class BuildInvocationScopeId extends ScopeId {

    public BuildInvocationScopeId(UniqueId id) {
        super(id);
    }

}