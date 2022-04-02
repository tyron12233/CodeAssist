package com.tyron.builder.api.internal.operations;

public interface BuildOperationMetadata {
    BuildOperationMetadata NONE = new BuildOperationMetadata() {
        @Override
        public String toString() {
            return "NONE";
        }
    };
}