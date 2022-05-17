package com.tyron.completion.java.compiler.services;

import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Names;

/**
 *
 * @author lahvac
 */
public class NBTreeMaker extends TreeMaker {

    public static void preRegister(Context context) {
        context.put(treeMakerKey, (Context.Factory<TreeMaker>) NBTreeMaker::new);
    }

    private final Names names;
    private final Types types;
    private final Symtab syms;

    protected NBTreeMaker(Context context) {
        super(context);
        this.names = Names.instance(context);
        this.types = Types.instance(context);
        this.syms = Symtab.instance(context);
    }

    protected NBTreeMaker(JCCompilationUnit toplevel, Names names, Types types, Symtab syms) {
        super(toplevel, names, types, syms);
        this.names = names;
        this.types = types;
        this.syms = syms;
    }

    @Override
    public TreeMaker forToplevel(JCCompilationUnit toplevel) {
        return new NBTreeMaker(toplevel, names, types, syms);
    }

}