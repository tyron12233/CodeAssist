/*
 * Copyright (c) 2005, 2006, Oracle and/or its affiliates. All rights reserved.
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

import javax.lang.model.element.*;
import javax.lang.model.util.Types;

/**
 * Represents a type in the Java programming language.
 * Types include primitive types, declared types (class and interface types),
 * array types, type variables, and the null type.
 * Also represented are wildcard type arguments,
 * the signature and return types of executables,
 * and pseudo-types corresponding to packages and to the keyword {@code void}.
 *
 * <p> Types should be compared using the utility methods in {@link
 * Types}.  There is no guarantee that any particular type will always
 * be represented by the same object.
 *
 * <p> To implement operations based on the class of an {@code
 * TypeMirror} object, either use a {@linkplain TypeVisitor visitor}
 * or use the result of the {@link #getKind} method.  Using {@code
 * instanceof} is <em>not</em> necessarily a reliable idiom for
 * determining the effective class of an object in this modeling
 * hierarchy since an implementation may choose to have a single
 * object implement multiple {@code TypeMirror} subinterfaces.
 *
 * @author Joseph D. Darcy
 * @author Scott Seligman
 * @author Peter von der Ah&eacute;
 * @see Element
 * @see Types
 * @since 1.6
 */
public interface TypeMirror {

    /**
     * Returns the {@code kind} of this type.
     *
     * @return the kind of this type
     */
    TypeKind getKind();

    /**
     * Obeys the general contract of {@link Object#equals Object.equals}.
     * This method does not, however, indicate whether two types represent
     * the same type.
     * Semantic comparisons of type equality should instead use
     * {@link Types#isSameType(TypeMirror, TypeMirror)}.
     * The results of {@code t1.equals(t2)} and
     * {@code Types.isSameType(t1, t2)} may differ.
     *
     * @param obj  the object to be compared with this type
     * @return {@code true} if the specified object is equal to this one
     */
    boolean equals(Object obj);

    /**
     * Obeys the general contract of {@link Object#hashCode Object.hashCode}.
     *
     * @see #equals
     */
    int hashCode();

    /**
     * Returns an informative string representation of this type.  If
     * possible, the string should be of a form suitable for
     * representing this type in source code.  Any names embedded in
     * the result are qualified if possible.
     *
     * @return a string representation of this type
     */
    String toString();

    /**
     * Applies a visitor to this type.
     *
     * @param <R> the return type of the visitor's methods
     * @param <P> the type of the additional parameter to the visitor's methods
     * @param v   the visitor operating on this type
     * @param p   additional parameter to the visitor
     * @return a visitor-specified result
     */
    <R, P> R accept(TypeVisitor<R, P> v, P p);
}
