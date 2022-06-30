package com.tyron.builder.internal.execution.history;

import com.tyron.builder.internal.execution.history.changes.Change;

import java.util.Objects;

public class DescriptiveChange implements Change {
    private final String message;

    public DescriptiveChange(String message, Object... params) {
        this.message = String.format(message, params);
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return getMessage();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DescriptiveChange that = (DescriptiveChange) o;
        return Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message);
    }
}