package org.openjdk.javax.xml.bind.annotation;

/**
 * Used by XmlAccessorOrder to control the ordering of properties and
 * fields in a JAXB bound class.
 *
 * @author Sekhar Vajjhala, Sun Microsystems, Inc.
 * @since 1.6, JAXB 2.0
 * @see XmlAccessorOrder
 */

public enum XmlAccessOrder { 
    /**
     * The ordering of fields and properties in a class is undefined. 
     */
    UNDEFINED,
    /**
     * The ordering of fields and properties in a class is in
     * alphabetical order as determined by the
     * method java.lang.String.compareTo(String anotherString).
     */
    ALPHABETICAL
}