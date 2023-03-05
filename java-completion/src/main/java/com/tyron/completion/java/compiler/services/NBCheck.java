package com.tyron.completion.java.compiler.services;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.comp.Check;
import com.sun.tools.javac.util.Context;

public class NBCheck extends Check {

    public static void preRegister(Context context) {
        context.put(checkKey, (Context.Factory<Check>) NBCheck::new);
    }

    protected NBCheck(Context context) {
        super(context);
    }

    @Override
    public void clearLocalClassNameIndexes(Symbol.ClassSymbol c) {
        if (c.owner != null && c.owner.enclClass() != null) {
            super.clearLocalClassNameIndexes(c);
        }
    }
}
