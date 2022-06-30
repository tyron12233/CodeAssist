package com.tyron.completion.java.action.context;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.R;
import com.tyron.completion.java.action.CommonJavaContextKeys;
import com.tyron.completion.java.util.TreeUtil;
import com.tyron.completion.util.RewriteUtil;
import com.tyron.completion.java.rewrite.IntroduceLocalVariable;
import com.tyron.completion.java.rewrite.JavaRewrite;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.editor.Editor;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Type;

import java.io.File;
import java.util.List;

public class IntroduceLocalVariableAction extends AnAction {

    public static final String ID = "javaIntroduceLocalVariableAction";

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!ActionPlaces.EDITOR.equals(event.getPlace())) {
            return;
        }

        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }

        JavaCompilerService compiler = event.getData(CommonJavaContextKeys.COMPILER);
        if (compiler == null) {
            return;
        }

        File file = event.getData(CommonDataKeys.FILE);
        if (file == null) {
            return;
        }

        TreePath currentPath = event.getData(CommonJavaContextKeys.CURRENT_PATH);
        if (currentPath == null) {
            return;
        }

        if (ActionUtil.canIntroduceLocalVariable(currentPath) == null) {
            return;
        }

        presentation.setVisible(true);
        presentation.setText(event.getDataContext().getString(R.string.menu_quickfix_introduce_local_variable_title));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        TreePath currentPath = e.getRequiredData(CommonJavaContextKeys.CURRENT_PATH);
        File file = e.getRequiredData(CommonDataKeys.FILE);
        JavaCompilerService compiler = e.getRequiredData(CommonJavaContextKeys.COMPILER);
        CompilerContainer cachedContainer = compiler.getCachedContainer();

        JavaRewrite rewrite = cachedContainer.get(task -> {
            if (task != null) {
                TreePath path = ActionUtil.canIntroduceLocalVariable(currentPath);
                return performInternal(task, path, file);
            }
            return null;
        });

        if (rewrite != null) {
            RewriteUtil.performRewrite(editor, file, compiler, rewrite);
        }
    }

    private JavaRewrite performInternal(CompileTask task, TreePath path, File file) {
        if (path.getLeaf().getKind() == Tree.Kind.ERRONEOUS) {
            ErroneousTree leaf = (ErroneousTree) path.getLeaf();
            List<? extends Tree> errorTrees = leaf.getErrorTrees();
            if (errorTrees != null && !errorTrees.isEmpty()) {
                path = new TreePath(path.getParentPath(), errorTrees.get(0));
            }
        }
        Trees trees = task.getTrees();
        Element element = trees.getElement(path);

        if (element == null) {
            return null;
        }

        TypeMirror typeMirror = trees.getTypeMirror(path);

        if (typeMirror == null || typeMirror.getKind() == TypeKind.ERROR) {
            // information is incomplete and type cannot be determined, default to Object
            typeMirror = task.task.getElements()
                    .getTypeElement("java.lang.Object")
                    .asType();
        }

        if (typeMirror != null) {
            if (typeMirror.getKind() == TypeKind.TYPEVAR) {
                if (((Type.TypeVar) typeMirror).isCaptured()) {
                    typeMirror = ((Type.TypeVar) typeMirror).getUpperBound();
                }
            }
            if (typeMirror.getKind() == TypeKind.EXECUTABLE) {
                // use the return type of the method
                typeMirror = ((ExecutableType) typeMirror).getReturnType();
            }
            return rewrite(typeMirror, trees, path, file, element.getSimpleName().toString());
        }

        if (element instanceof ExecutableElement) {
            TypeMirror returnType = ActionUtil.getReturnType(task.task, path,
                    (ExecutableElement) element);
            if (returnType != null) {
                return rewrite(returnType, trees, path, file, element.getSimpleName().toString());
            }
        }
        return null;
    }

    private JavaRewrite rewrite(TypeMirror type,
                                Trees trees,
                                TreePath path,
                                File file,
                                String methodName) {
        SourcePositions pos = trees.getSourcePositions();
        long startPosition = pos.getStartPosition(path.getCompilationUnit(),
                path.getLeaf());
        return new IntroduceLocalVariable(file.toPath(),
                methodName, type, startPosition);
    }
}
