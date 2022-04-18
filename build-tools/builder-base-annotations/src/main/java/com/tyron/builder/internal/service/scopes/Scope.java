package com.tyron.builder.internal.service.scopes;

public interface Scope {
    /**
     * These services are reused across builds in the same process.
     *
     * <p>Global services are visible to all other services.</p>
     */
    interface Global extends Scope {}
}