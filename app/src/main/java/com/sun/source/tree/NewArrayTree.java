/*
 * Copyright (c) 2005, Oracle and/or its affiliates. All rights reserved.
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
 * A tree node for an expression to create a new instance of an array.
 *
 * For example:
 * <pre>
 *   new <em>type</em> <em>dimensions</em> <em>initializers</em>
 *
 *   new <em>type</em> <em>dimensions</em> [ ] <em>initializers</em>
 * </pre>
 *
 * @jls section 15.10
 *
 * @author Peter von der Ah&eacute;
 * @author Jonathan Gibbons
 * @since 1.6
 */
public interface NewArrayTree extends ExpressionTree {
    Tree getType();
    List<? extends ExpressionTree> getDimensions();
    List<? extends ExpressionTree> getInitializers();
}
