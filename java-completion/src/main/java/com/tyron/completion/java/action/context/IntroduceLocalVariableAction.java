package com.tyron.completion.java.action.context;

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
import com.tyron.completion.util.RewriteUtil;
import com.tyron.completion.java.rewrite.IntroduceLocalVariable;
import com.tyron.completion.java.rewrite.JavaRewrite;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.editor.Editor;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.type.ErrorType;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.io.File;

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

        if (!ActionUtil.canIntroduceLocalVariable(currentPath)) {
            return;
        }

        presentation.setVisible(true);
        presentation.setText(event.getDataContext().getString(R.string.menu_quickfix_introduce_local_variable_title));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        TreePath currentPath = e.getData(CommonJavaContextKeys.CURRENT_PATH);
        File file = e.getData(CommonDataKeys.FILE);
        JavaCompilerService compiler = e.getRequiredData(CommonJavaContextKeys.COMPILER);
        CompilerContainer cachedContainer = compiler.getCachedContainer();

        JavaRewrite rewrite = cachedContainer.get(task -> {
            if (task != null) {
                return performInternal(task, currentPath, file);
            }
            return null;
        });

        if (rewrite != null) {
            RewriteUtil.performRewrite(editor, file, compiler, rewrite);
        }
    }

    private JavaRewrite performInternal(CompileTask task, TreePath path, File file) {
        Trees trees = Trees.instance(task.task);
        Element element = trees.getElement(path);
        TypeMirror typeMirror = trees.getTypeMirror(path);

        if (typeMirror instanceof ErrorType) {
            // information is incomplete and type cannot be determined, default to Object
            typeMirror = task.task.getElements()
                    .getTypeElement("java.lang.Object")
                    .asType();
        }
        if (typeMirror != null) {
            return rewrite(typeMirror, trees, path, file, element.getSimpleName().toString());
        }

        if (element instanceof ExecutableElement) {
            TypeMirror returnType = ActionUtil.getReturnType(task.task, path,
                    (ExecutableElement) element);
            return rewrite(returnType, trees, path, file, element.getSimpleName().toString());
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
