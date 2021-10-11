package com.tyron.layoutpreview.model;

public enum Format {
    BOOLEAN,
    STRING,
    FLOAT,
    DIMENSION,
    INTEGER,

    COLOR,

    /**
     * Used to allow valued that are references
     */
    REFERENCE,

    /**
     * Used to only allow certain values
     */
    ENUM,

}
