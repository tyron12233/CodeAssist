package com.tyron.completion.java.compiler.services;

import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.JavacScope;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.element.Element;

/**
 *
 * @author lahvac
 */
public class NBJavacTrees extends JavacTrees {

    private final Map<Element, TreePath> element2paths = new HashMap<>();
    
    public static void preRegister(Context context) {
        context.put(JavacTrees.class, (Context.Factory<JavacTrees>) NBJavacTrees::new);
    }
    protected NBJavacTrees(Context context) {
        super(context);
    }
    @Override
    public TreePath getPath(Element e) {
        TreePath path = super.getPath(e);
        return path != null ? path : element2paths.get(e);
    }

    void addPathForElement(Element elem, TreePath path) {
        element2paths.put(elem, path);
    }

    @Override
    public Symbol getElement(TreePath path) {
        return TreeInfo.symbolFor((JCTree) path.getLeaf());
    }

    @Override
    protected Copier createCopier(TreeMaker maker) {
        return new Copier(maker) {
            @Override
            public JCTree visitVariable(VariableTree node, JCTree p) {
                JCVariableDecl old = (JCVariableDecl) node;
                JCVariableDecl nue = (JCVariableDecl) super.visitVariable(node, p);
                if (old.sym != null) {
                    nue.mods.flags |= old.sym.flags_field & Flags.EFFECTIVELY_FINAL;
                }
                return nue;
            }
        };
    }

}