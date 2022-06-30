package com.tyron.completion.java.compiler.services;

import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Pair;
import java.util.Collection;
import java.util.Collections;
import java.util.Queue;
import java.util.function.Consumer;

/**
 *
 * @author lahvac
 */
public class NBJavaCompiler extends JavaCompiler {

    public static void preRegister(Context context) {
        context.put(compilerKey, (Context.Factory<JavaCompiler>) NBJavaCompiler::new);
    }

    private final CancelService cancelService;
    private Consumer<Env<AttrContext>> desugarCallback;

    public NBJavaCompiler(Context context) {
        super(context);
        cancelService = CancelService.instance(context);
    }

    @Override
    public void processAnnotations(List<JCTree.JCCompilationUnit> roots, Collection<String> classnames) {
        if (roots.isEmpty()) {
            super.processAnnotations(roots, classnames);
        } else {
            setOrigin(roots.head.sourcefile.toUri().toString());
            try {
                super.processAnnotations(roots, classnames);
            } finally {
                setOrigin("");
            }
        }
    }

    private void setOrigin(String origin) {
        fileManager.handleOption("apt-origin", Collections.singletonList(origin).iterator());
    }

    public void setDesugarCallback(Consumer<Env<AttrContext>> callback) {
        this.desugarCallback = callback;
    }

    private boolean desugaring;

    @Override
    protected void desugar(Env<AttrContext> env, Queue<Pair<Env<AttrContext>, JCTree.JCClassDecl>> results) {
        boolean prevDesugaring = desugaring;
        try {
            desugaring = true;
        super.desugar(env, results);
        } finally {
            desugaring = prevDesugaring;
        }
    }

    void maybeInvokeDesugarCallback(Env<AttrContext> env) {
        if (desugaring && desugarCallback != null) {
            desugarCallback.accept(env);
        }
    }

}