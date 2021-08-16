/*
 * Copyright (c) 2010, 2011, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.source.tree;

import java.util.List;

/**
 * A tree node for a union type expression in a multicatch var declaration.
 *
 * @author Maurizio Cimadamore
 *
 * @since 1.7
 */
public interface UnionTypeTree extends Tree {
    List<? extends Tree> getTypeAlternatives();
}
