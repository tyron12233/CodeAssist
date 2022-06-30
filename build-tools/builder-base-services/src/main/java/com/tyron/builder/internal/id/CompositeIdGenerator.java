package com.tyron.builder.internal.id;

import java.io.Serializable;

public class CompositeIdGenerator implements IdGenerator<Object> {
    private final Object scope;
    private final IdGenerator<?> generator;

    public CompositeIdGenerator(Object scope, IdGenerator<?> generator) {
        this.scope = scope;
        this.generator = generator;
    }

    @Override
    public Object generateId() {
        return new CompositeId(scope, generator.generateId());
    }

    public static class CompositeId implements Serializable {
        private final Object scope;
        private final Object id;

        public CompositeId(Object scope, Object id) {
            this.id = id;
            this.scope = scope;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (o == null || o.getClass() != getClass()) {
                return false;
            }

            CompositeId other = (CompositeId) o;
            return other.id.equals(id) && other.scope.equals(scope);
        }

        @Override
        public int hashCode() {
            return scope.hashCode() ^ id.hashCode();
        }

        public Object getScope() {
            return scope;
        }

        public Object getId() {
            return id;
        }

        @Override
        public String toString() {
            return scope + "." + id;
        }
    }
}
