package org.openjdk.javax.xml.bind.annotation;

/**
 * Used by XmlAccessorType to control serialization of fields or
 * properties. 
 *
 * @author Sekhar Vajjhala, Sun Microsystems, Inc.
 * @since 1.6, JAXB 2.0
 * @see XmlAccessorType
 */

public enum XmlAccessType {
    /**
     * Every getter/setter pair in a JAXB-bound class will be automatically
     * bound to XML, unless annotated by {@link XmlTransient}.
     *
     * Fields are bound to XML only when they are explicitly annotated
     * by some of the JAXB annotations.
     */
    PROPERTY,
    /**
     * Every non static, non transient field in a JAXB-bound class will be automatically
     * bound to XML, unless annotated by {@link XmlTransient}.
     *
     * Getter/setter pairs are bound to XML only when they are explicitly annotated
     * by some of the JAXB annotations.
     */
    FIELD,
    /**
     * Every public getter/setter pair and every public field will be
     * automatically bound to XML, unless annotated by {@link XmlTransient}.
     *
     * Fields or getter/setter pairs that are private, protected, or 
     * defaulted to package-only access are bound to XML only when they are
     * explicitly annotated by the appropriate JAXB annotations.
     */
    PUBLIC_MEMBER,
    /**
     * None of the fields or properties is bound to XML unless they
     * are specifically  annotated with some of the JAXB annotations.
     */
    NONE
}