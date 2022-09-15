package com.tyron.completion.java.action.context;

import androidx.annotation.NonNull;

import com.android.tools.r8.internal.P;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.completion.java.R;
import com.tyron.completion.java.action.FindCurrentPath;
import com.tyron.completion.java.parse.CompilationInfo;
import com.tyron.completion.java.provider.DefaultJavacUtilitiesProvider;
import com.tyron.completion.java.rewrite.IntroduceLocalVariable;
import com.tyron.completion.java.rewrite.JavaRewrite2;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.util.RewriteUtil;
import com.tyron.editor.Editor;

import java.io.File;
import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

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

        File file = event.getData(CommonDataKeys.FILE);
        if (file == null) {
            return;
        }

        CompilationInfo compilationInfo = event.getData(CompilationInfo.COMPILATION_INFO_KEY);
        if (compilationInfo == null) {
            return;
        }

        JCTree.JCCompilationUnit unit = compilationInfo.getCompilationUnit(file.toURI());
        if (unit == null) {
            return;
        }

        int left = editor.getCaret().getStart();
        int right = editor.getCaret().getEnd();
        JavacTaskImpl javacTask = compilationInfo.impl.getJavacTask();
        TreePath currentPath = new FindCurrentPath(javacTask).scan(unit, left, right);

        if (ActionUtil.canIntroduceLocalVariable(currentPath) == null) {
            return;
        }

        presentation.setVisible(true);
        presentation.setText(event.getDataContext()
                .getString(R.string.menu_quickfix_introduce_local_variable_title));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        File file = e.getRequiredData(CommonDataKeys.FILE);

        CompilationInfo compilationInfo = e.getRequiredData(CompilationInfo.COMPILATION_INFO_KEY);
        JavacTaskImpl javacTask = compilationInfo.impl.getJavacTask();
        JCTree.JCCompilationUnit unit = compilationInfo.getCompilationUnit(file.toURI());
        TreePath currentPath =
                new FindCurrentPath(javacTask).scan(unit, editor.getCaret().getStart(),
                        editor.getCaret().getEnd());
        TreePath path = ActionUtil.canIntroduceLocalVariable(currentPath);
        JavaRewrite2 rewrite = performInternal(javacTask, path, file);

        if (rewrite != null) {
            RewriteUtil.performRewrite(editor, file,
                    new DefaultJavacUtilitiesProvider(javacTask, unit, editor.getProject()), rewrite);
        }
    }

    private JavaRewrite2 performInternal(JavacTaskImpl task, TreePath path, File file) {
        if (path.getLeaf().getKind() == Tree.Kind.ERRONEOUS) {
            ErroneousTree leaf = (ErroneousTree) path.getLeaf();
            List<? extends Tree> errorTrees = leaf.getErrorTrees();
            if (errorTrees != null && !errorTrees.isEmpty()) {
                path = new TreePath(path.getParentPath(), errorTrees.get(0));
            }
        }
        Trees trees = Trees.instance(task);
        Element element = trees.getElement(path);

        if (element == null) {
            return null;
        }

        if (element instanceof ExecutableElement) {
            TypeMirror returnType =
                    ActionUtil.getReturnType(task, path, (ExecutableElement) element);
            if (returnType instanceof TypeVariable) {
                TypeVariable typeVariable = (TypeVariable) returnType;

                TypeMirror lowerBound = typeVariable.getLowerBound();
                TypeMirror upperBound = typeVariable.getUpperBound();
                if (lowerBound != null && lowerBound.getKind() != TypeKind.NULL) {
                    returnType = lowerBound;
                } else if (upperBound != null && upperBound.getKind() != TypeKind.NULL) {
                    returnType = upperBound;
                } else {
                    returnType = task.getElements().getTypeElement("java.lang.Object").asType();
                }
            }
            if (returnType != null) {
                if (returnType.getKind() == TypeKind.VOID) {
                    return null;
                }
                return rewrite(returnType, trees, path, file, element.getSimpleName().toString());
            }
        }

        TypeMirror typeMirror = trees.getTypeMirror(path);

        if (typeMirror == null || typeMirror.getKind() == TypeKind.ERROR) {
            // information is incomplete and type cannot be determined, default to Object
            typeMirror = task.getElements().getTypeElement("java.lang.Object").asType();
        }

        if (typeMirror != null) {
            if (typeMirror.getKind() == TypeKind.VOID) {
                return null;
            }
            if (typeMirror.getKind() == TypeKind.TYPEVAR) {
                typeMirror = ((Type.TypeVar) typeMirror).getUpperBound();
            }
            if (typeMirror.getKind() == TypeKind.EXECUTABLE) {
                // use the return type of the method
                typeMirror = ((ExecutableType) typeMirror).getReturnType();
            }
            return rewrite(typeMirror, trees, path, file, element.getSimpleName().toString());
        }
        return null;
    }

    private JavaRewrite2 rewrite(TypeMirror type,
                                 Trees trees,
                                 TreePath path,
                                 File file,
                                 String methodName) {
        SourcePositions pos = trees.getSourcePositions();
        long startPosition = pos.getStartPosition(path.getCompilationUnit(), path.getLeaf());
        return new IntroduceLocalVariable(file.toPath(), methodName, type, startPosition);
    }
}
