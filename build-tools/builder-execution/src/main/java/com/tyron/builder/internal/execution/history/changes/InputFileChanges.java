package com.tyron.builder.internal.execution.history.changes;

import com.tyron.builder.api.InvalidUserDataException;

public interface InputFileChanges extends ChangeContainer {
    boolean accept(String propertyName, ChangeVisitor visitor);

    InputFileChanges EMPTY = new InputFileChanges() {

        @Override
        public boolean accept(ChangeVisitor visitor) {
            return true;
        }

        @Override
        public boolean accept(String propertyName, ChangeVisitor visitor) {
            throw new InvalidUserDataException("Cannot query incremental changes for property " + propertyName + ": No incremental properties declared.");
        }
    };
}