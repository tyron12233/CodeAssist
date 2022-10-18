package com.tyron.completion.java.parse;

import static com.sun.source.tree.Tree.Kind;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.api.JavacScope;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.Log;
import com.tyron.completion.java.compiler.services.NBResolve;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

@SuppressWarnings("Since15")
public class TreeUtilities {

    public static final Set<Kind> CLASS_TREE_KINDS = EnumSet.of(Kind.ANNOTATION_TYPE, Kind.CLASS, Kind.ENUM, Kind.INTERFACE, Kind.RECORD);

    private final CompilationInfo info;

    public TreeUtilities(CompilationInfo info) {
        this.info = info;
    }

    public TypeMirror reattributeTree(Tree tree, Scope scope) {
        Env<AttrContext> env = getEnv(scope);
        try {
            return attributeTree(info.impl.getJavacTask(), env.toplevel, (JCTree) tree, scope, new ArrayList<>());
        } finally {
            NBResolve.instance(info.impl.getJavacTask().getContext()).restoreAccessbilityChecks();
        }
    }

    private static Env<AttrContext> getEnv(Scope scope) {
//        if (scope instanceof NBScope) {
//            scope = ((NBScope) scope).delegate;
//        }

//        if (scope instanceof HackScope) {
//            return ((HackScope) scope).getEnv();
//        }

        return ((JavacScope) scope).getEnv();
    }

    private static TypeMirror attributeTree(JavacTaskImpl jti, CompilationUnitTree cut, Tree tree, Scope scope, final List<Diagnostic<? extends JavaFileObject>> errors) {
        Log log = Log.instance(jti.getContext());
        Log.DiagnosticHandler discardHandler = new Log.DiscardDiagnosticHandler(log) {
            @Override
            public void report(JCDiagnostic diag) {
                errors.add(diag);
            }
        };
        NBResolve resolve = NBResolve.instance(jti.getContext());
        resolve.disableAccessibilityChecks();
        try {
            Attr attr = Attr.instance(jti.getContext());
            Env<AttrContext> env = getEnv(scope);
            if (tree instanceof JCTree.JCExpression)
                return attr.attribExpr((JCTree) tree,env, Type.noType);
            return attr.attribStat((JCTree) tree,env);
        } finally {
            unenter(jti.getContext(), (JCTree.JCCompilationUnit) cut, (JCTree) tree);
//            cacheContext.leave();
//            log.useSource(prev);
            log.popDiagnosticHandler(discardHandler);
            resolve.restoreAccessbilityChecks();
//            enter.shadowTypeEnvs(false);
        }
    }

    private static void unenter(Context ctx, JCTree.JCCompilationUnit cut, JCTree tree) {
        Enter.instance(ctx).unenter(cut, tree);
    }
}
