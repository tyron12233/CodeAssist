package com.tyron.builder.api;

/**
 * An enumeration for describing validation policies for file paths.
 */
public enum PathValidation {
    NONE(), EXISTS(), FILE(), DIRECTORY()
}