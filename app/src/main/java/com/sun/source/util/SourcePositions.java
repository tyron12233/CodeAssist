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

package com.sun.source.util;

import com.sun.source.tree.*;

/**
 * Provides methods to obtain the position of a Tree within a CompilationUnit.
 * A position is defined as a simple character offset from the start of a
 * CompilationUnit where the first character is at offset 0.
 *
 * @author Peter von der Ah&eacute;
 * @since 1.6
 */
public interface SourcePositions {

    /**
     * Gets the starting position of tree within file.  If tree is not found within
     * file, or if the starting position is not available,
     * return {@link javax.tools.Diagnostic#NOPOS}.
     * The returned position must be at the start of the yield of this tree, that
     * is for any sub-tree of this tree, the following must hold:
     *
     * <p>
     * {@code tree.getStartPosition() <= subtree.getStartPosition()} or <br>
     * {@code tree.getStartPosition() == NOPOS} or <br>
     * {@code subtree.getStartPosition() == NOPOS}
     * </p>
     *
     * @param file CompilationUnit in which to find tree.
     * @param tree tree for which a position is sought.
     * @return the start position of tree.
     */
     long getStartPosition(CompilationUnitTree file, Tree tree);

    /**
     * Gets the ending position of tree within file.  If tree is not found within
     * file, or if the starting position is not available,
     * return {@link javax.tools.Diagnostic#NOPOS}.
     * The returned position must be at the end of the yield of this tree,
     * that is for any sub-tree of this tree, the following must hold:
     *
     * <p>
     * {@code tree.getEndPosition() >= subtree.getEndPosition()} or <br>
     * {@code tree.getEndPosition() == NOPOS} or <br>
     * {@code subtree.getEndPosition() == NOPOS}
     * </p>
     *
     * In addition, the following must hold:
     *
     * <p>
     * {@code tree.getStartPosition() <= tree.getEndPosition()}  or <br>
     * {@code tree.getStartPosition() == NOPOS} or <br>
     * {@code tree.getEndPosition() == NOPOS}
     * </p>
     *
     * @param file CompilationUnit in which to find tree.
     * @param tree tree for which a position is sought.
     * @return the end position of tree.
     */
     long getEndPosition(CompilationUnitTree file, Tree tree);

}
