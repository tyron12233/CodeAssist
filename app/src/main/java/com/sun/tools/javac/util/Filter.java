/*
 * Copyright (c) 2010, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.util;

/**
 * Simple filter acting as a boolean predicate. Method accepts return true if
 * the supplied element matches against the filter.
 */
public interface Filter<T> {
    /**
     * Does this element match against the filter?
     * @param t element to be checked
     * @return true if the element satisfy constraints imposed by filter
     */
    boolean accepts(T t);
}
