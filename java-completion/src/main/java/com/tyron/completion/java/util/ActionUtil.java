package com.tyron.completion.java.util;

import androidx.annotation.NonNull;

import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.source.doctree.ThrowsTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.tree.JCTree;

public class ActionUtil {

    public static boolean canIntroduceLocalVariable(@NonNull TreePath path) {
        TreePath parent = path.getParentPath();
        if (parent == null) {
            return false;
        }

        TreePath grandParent = parent.getParentPath();
        if (grandParent.getLeaf() instanceof ThrowsTree) {
            return false;
        }
        return !(grandParent.getLeaf() instanceof JCTree.JCVariableDecl);
    }

    public static TypeMirror getReturnType(JavacTask task, TreePath path, ExecutableElement element) {
        return path.getLeaf() instanceof NewClassTree ?
                Trees.instance(task).getTypeMirror(path) :
                ((ExecutableElement) element).getReturnType();
    }
}
