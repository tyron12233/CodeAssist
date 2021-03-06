package com.tyron.completion.java.compiler.services;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.util.Context;

/**
 *
 * @author lahvac
 */
public class NBEnter extends Enter {

    public static void preRegister(Context context) {
        context.put(enterKey, new Context.Factory<Enter>() {
            public Enter make(Context c) {
                return new NBEnter(c);
            }
        });
    }

    private final CancelService cancelService;
    private final Symtab syms;
    private final NBJavaCompiler compiler;

    public NBEnter(Context context) {
        super(context);
        cancelService = CancelService.instance(context);
        syms = Symtab.instance(context);
        JavaCompiler c = JavaCompiler.instance(context);
        compiler = c instanceof NBJavaCompiler ? (NBJavaCompiler) c : null;
    }

    @Override
    public void visitClassDef(JCClassDecl tree) {
        cancelService.abortIfCanceled();
        super.visitClassDef(tree);
    }

    @Override
    public void visitTopLevel(JCTree.JCCompilationUnit tree) {
        if (TreeInfo.isModuleInfo(tree) && tree.modle == syms.noModule) {
            //workaround: when source level == 8, then visitTopLevel crashes for module-info.java
            return ;
        }
        super.visitTopLevel(tree);
    }

    @Override
    public Env<AttrContext> getEnv(TypeSymbol sym) {
        Env<AttrContext> env = super.getEnv(sym);
        if (compiler != null) {
            compiler.maybeInvokeDesugarCallback(env);
        }
        return env;
    }

}