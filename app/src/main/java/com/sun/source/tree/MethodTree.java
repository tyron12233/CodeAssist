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

package com.sun.source.tree;

import java.util.List;
import javax.lang.model.element.Name;

/**
 * A tree node for a method or annotation type element declaration.
 *
 * For example:
 * <pre>
 *   <em>modifiers</em> <em>typeParameters</em> <em>type</em> <em>name</em>
 *      ( <em>parameters</em> )
 *      <em>body</em>
 *
 *   <em>modifiers</em> <em>type</em> <em>name</em> () default <em>defaultValue</em>
 * </pre>
 *
 * @jls sections 8.4, 8.6, 8.7, 9.4, and 9.6
 *
 * @author Peter von der Ah&eacute;
 * @author Jonathan Gibbons
 * @since 1.6
 */
public interface MethodTree extends Tree {
    ModifiersTree getModifiers();
    Name getName();
    Tree getReturnType();
    List<? extends TypeParameterTree> getTypeParameters();
    List<? extends VariableTree> getParameters();
    List<? extends ExpressionTree> getThrows();
    BlockTree getBody();
    Tree getDefaultValue(); // for annotation types
}
