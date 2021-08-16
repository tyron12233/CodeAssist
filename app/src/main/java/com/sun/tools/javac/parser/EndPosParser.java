/*
 * Copyright (c) 2005, 2008, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.parser;

import java.util.Map;
import java.util.HashMap;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;

import static com.sun.tools.javac.tree.JCTree.*;

/**
 * This class is similar to Parser except that it stores ending
 * positions for the tree nodes.
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own risk.
 * This code and its internal interfaces are subject to change or
 * deletion without notice.</b></p>
 */
public class EndPosParser extends JavacParser {

    public EndPosParser(ParserFactory fac, Lexer S, boolean keepDocComments, boolean keepLineMap) {
        super(fac, S, keepDocComments, keepLineMap);
        this.S = S;
        endPositions = new HashMap<JCTree,Integer>();
    }

    private Lexer S;

    /** A hashtable to store ending positions
     *  of source ranges indexed by the tree nodes.
     *  Defined only if option flag genEndPos is set.
     */
    Map<JCTree, Integer> endPositions;

    /** {@inheritDoc} */
    @Override
    protected void storeEnd(JCTree tree, int endpos) {
        int errorEndPos = getErrorEndPos();
        endPositions.put(tree, errorEndPos > endpos ? errorEndPos : endpos);
    }

    /** {@inheritDoc} */
    @Override
    protected <T extends JCTree> T to(T t) {
        storeEnd(t, S.endPos());
        return t;
    }

    /** {@inheritDoc} */
    @Override
    protected <T extends JCTree> T toP(T t) {
        storeEnd(t, S.prevEndPos());
        return t;
    }

    @Override
    public JCCompilationUnit parseCompilationUnit() {
        JCCompilationUnit t = super.parseCompilationUnit();
        t.endPositions = endPositions;
        return t;
    }

    /** {@inheritDoc} */
    @Override
    JCExpression parExpression() {
        int pos = S.pos();
        JCExpression t = super.parExpression();
        return toP(F.at(pos).Parens(t));
    }

    /** {@inheritDoc} */
    @Override
    public int getEndPos(JCTree tree) {
        return TreeInfo.getEndPos(tree, endPositions);
    }

}
