package com.tyron.code.analyzer.semantic;

import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;

import java.util.concurrent.atomic.AtomicBoolean;

public class SemanticHighlighter extends TreePathScanner<Void, Void> {

    private final AtomicBoolean mCancelFlag = new AtomicBoolean(false);

    public void cancel() {
        mCancelFlag.set(true);
    }

    @Override
    public Void scan(Tree tree, Void unused) {
        if (mCancelFlag.get()) {
            return null;
        }
        return super.scan(tree, unused);
    }

    @Override
    public Void scan(Iterable<? extends Tree> iterable, Void unused) {
        if (mCancelFlag.get()) {
            return null;
        }
        return super.scan(iterable, unused);
    }

    @Override
    public Void scan(TreePath treePath, Void unused) {
        if (mCancelFlag.get()) {
            return null;
        }
        return super.scan(treePath, unused);
    }

    @Override
    public Void reduce(Void unused, Void r1) {
        if (mCancelFlag.get()) {
            return null;
        }
        return super.reduce(unused, r1);
    }
}
