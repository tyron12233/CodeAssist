package com.tyron.builder.compiler.manifest;

/**
 * Defines conversion routines for named types that can be converted into Xml name or Camel case
 * names.
 */
public interface ConvertibleName {

    /**
     * Returns a xml lower-hyphen separated name of itself.
     */
    String toXmlName();

    /**
     * Returns a camel case version of itself.
     */
    String toCamelCaseName();
}
