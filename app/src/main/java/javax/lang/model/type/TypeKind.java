/*
 * Copyright (c) 2005, 2011, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package javax.lang.model.type;


/**
 * The kind of a type mirror.
 *
 * <p>Note that it is possible additional type kinds will be added to
 * accommodate new, currently unknown, language structures added to
 * future versions of the Java&trade; programming language.
 *
 * @author Joseph D. Darcy
 * @author Scott Seligman
 * @author Peter von der Ah&eacute;
 * @see TypeMirror
 * @since 1.6
 */
public enum TypeKind {
    /**
     * The primitive type {@code boolean}.
     */
    BOOLEAN,

    /**
     * The primitive type {@code byte}.
     */
    BYTE,

    /**
     * The primitive type {@code short}.
     */
    SHORT,

    /**
     * The primitive type {@code int}.
     */
    INT,

    /**
     * The primitive type {@code long}.
     */
    LONG,

    /**
     * The primitive type {@code char}.
     */
    CHAR,

    /**
     * The primitive type {@code float}.
     */
    FLOAT,

    /**
     * The primitive type {@code double}.
     */
    DOUBLE,

    /**
     * The pseudo-type corresponding to the keyword {@code void}.
     * @see NoType
     */
    VOID,

    /**
     * A pseudo-type used where no actual type is appropriate.
     * @see NoType
     */
    NONE,

    /**
     * The null type.
     */
    NULL,

    /**
     * An array type.
     */
    ARRAY,

    /**
     * A class or interface type.
     */
    DECLARED,

    /**
     * A class or interface type that could not be resolved.
     */
    ERROR,

    /**
     * A type variable.
     */
    TYPEVAR,

    /**
     * A wildcard type argument.
     */
    WILDCARD,

    /**
     * A pseudo-type corresponding to a package element.
     * @see NoType
     */
    PACKAGE,

    /**
     * A method, constructor, or initializer.
     */
    EXECUTABLE,

    /**
     * An implementation-reserved type.
     * This is not the type you are looking for.
     */
    OTHER,

    /**
      * A union type.
      *
      * @since 1.7
      */
    UNION;

    /**
     * Returns {@code true} if this kind corresponds to a primitive
     * type and {@code false} otherwise.
     * @return {@code true} if this kind corresponds to a primitive type
     */
    public boolean isPrimitive() {
        switch(this) {
        case BOOLEAN:
        case BYTE:
        case SHORT:
        case INT:
        case LONG:
        case CHAR:
        case FLOAT:
        case DOUBLE:
            return true;

        default:
            return false;
        }
    }
}
