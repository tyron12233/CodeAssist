package com.tyron.builder.internal.operations;

public interface BuildOperationMetadata {
    BuildOperationMetadata NONE = new BuildOperationMetadata() {
        @Override
        public String toString() {
            return "NONE";
        }
    };
}