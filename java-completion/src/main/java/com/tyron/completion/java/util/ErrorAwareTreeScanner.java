package com.tyron.completion.java.util;

import com.sun.source.tree.ErroneousTree;
import com.sun.source.util.TreePathScanner;

public class ErrorAwareTreeScanner<R, P> extends TreePathScanner<R, P> {

    @Override
    public R visitErroneous(ErroneousTree et, P p) {
        return scan(et.getErrorTrees(), p);
    }
}
