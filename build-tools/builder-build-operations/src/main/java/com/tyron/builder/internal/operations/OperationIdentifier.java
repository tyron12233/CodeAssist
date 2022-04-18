package com.tyron.builder.internal.operations;

import java.io.Serializable;

public class OperationIdentifier implements Serializable {

    private final long id;

    public OperationIdentifier(long id) {
        if (id == 0) {
            throw new IllegalArgumentException("Operation ID value must be non-zero");
        }
        this.id = id;
    }

    public long getId() {
        return id;
    }

    @Override
    public String toString() {
        return String.valueOf(id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        OperationIdentifier that = (OperationIdentifier) o;
        return id == that.id;
    }

    public int hashCode() {
        return (int) (id ^ (id >>> 32));
    }
}