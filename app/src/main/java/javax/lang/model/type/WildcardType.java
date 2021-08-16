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


/**
 * Represents a wildcard type argument.
 * Examples include:    <pre><tt>
 *   ?
 *   ? extends Number
 *   ? super T
 * </tt></pre>
 *
 * <p> A wildcard may have its upper bound explicitly set by an
 * {@code extends} clause, its lower bound explicitly set by a
 * {@code super} clause, or neither (but not both).
 *
 * @author Joseph D. Darcy
 * @author Scott Seligman
 * @author Peter von der Ah&eacute;
 * @since 1.6
 */
public interface WildcardType extends TypeMirror {

    /**
     * Returns the upper bound of this wildcard.
     * If no upper bound is explicitly declared,
     * {@code null} is returned.
     *
     * @return the upper bound of this wildcard
     */
    TypeMirror getExtendsBound();

    /**
     * Returns the lower bound of this wildcard.
     * If no lower bound is explicitly declared,
     * {@code null} is returned.
     *
     * @return the lower bound of this wildcard
     */
    TypeMirror getSuperBound();
}
