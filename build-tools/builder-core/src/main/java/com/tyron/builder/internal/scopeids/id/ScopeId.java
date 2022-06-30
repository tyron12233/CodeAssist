package com.tyron.builder.internal.scopeids.id;

import com.tyron.builder.internal.id.UniqueId;

public class ScopeId {

    private final UniqueId id;

    ScopeId(UniqueId id) {
        this.id = id;
    }

    public UniqueId getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ScopeId scopeId = (ScopeId) o;

        return id.equals(scopeId.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

}