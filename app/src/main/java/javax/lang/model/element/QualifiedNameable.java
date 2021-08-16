/*
 * Copyright (c) 2009, Oracle and/or its affiliates. All rights reserved.
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

package javax.lang.model.element;

/**
 * A mixin interface for an element that has a qualified name.
 *
 * @author Joseph D. Darcy
 * @since 1.7
 */
public interface QualifiedNameable extends Element {
    /**
     * Returns the fully qualified name of an element.
     *
     * @return the fully qualified name of an element
     */
    Name getQualifiedName();
}
