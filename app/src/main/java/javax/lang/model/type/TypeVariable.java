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


import javax.lang.model.element.Element;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.util.Types;


/**
 * Represents a type variable.
 * A type variable may be explicitly declared by a
 * {@linkplain TypeParameterElement type parameter} of a
 * type, method, or constructor.
 * A type variable may also be declared implicitly, as by
 * the capture conversion of a wildcard type argument
 * (see chapter 5 of
 * <cite>The Java&trade; Language Specification</cite>).
 *
 * @author Joseph D. Darcy
 * @author Scott Seligman
 * @author Peter von der Ah&eacute;
 * @see TypeParameterElement
 * @since 1.6
 */
public interface TypeVariable extends ReferenceType {

    /**
     * Returns the element corresponding to this type variable.
     *
     * @return the element corresponding to this type variable
     */
    Element asElement();

    /**
     * Returns the upper bound of this type variable.
     *
     * <p> If this type variable was declared with no explicit
     * upper bounds, the result is {@code java.lang.Object}.
     * If it was declared with multiple upper bounds,
     * the result is an intersection type (modeled as a
     * {@link DeclaredType}).
     * Individual bounds can be found by examining the result's
     * {@linkplain Types#directSupertypes(TypeMirror) supertypes}.
     *
     * @return the upper bound of this type variable
     */
    TypeMirror getUpperBound();

    /**
     * Returns the lower bound of this type variable.  While a type
     * parameter cannot include an explicit lower bound declaration,
     * capture conversion can produce a type variable with a
     * non-trivial lower bound.  Type variables otherwise have a
     * lower bound of {@link NullType}.
     *
     * @return the lower bound of this type variable
     */
    TypeMirror getLowerBound();
}
