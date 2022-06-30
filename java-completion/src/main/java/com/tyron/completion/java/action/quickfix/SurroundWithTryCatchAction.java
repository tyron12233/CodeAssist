package com.tyron.completion.java.action.quickfix;

import androidx.annotation.NonNull;

import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.R;
import com.tyron.completion.java.action.CommonJavaContextKeys;
import com.tyron.completion.util.RewriteUtil;
import com.tyron.completion.java.rewrite.AddTryCatch;
import com.tyron.completion.java.rewrite.JavaRewrite;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.java.util.DiagnosticUtil;
import com.tyron.common.util.ThreadUtil;
import com.tyron.editor.Editor;

import javax.tools.Diagnostic;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree;

import java.io.File;
import java.util.Locale;

public class SurroundWithTryCatchAction extends ExceptionsQuickFix {

    public static final String ID = "javaSurroundWithTryCatchQuickFix";

    @Override
    public void update(@NonNull AnActionEvent event) {
        super.update(event);

        Presentation presentation = event.getPresentation();
        if (!presentation.isVisible()) {
            return;
        }

        presentation.setVisible(false);
        Diagnostic<?> diagnostic = event.getData(CommonDataKeys.DIAGNOSTIC);
        if (diagnostic == null) {
            return;
        }

        TreePath surroundingPath =
                ActionUtil.findSurroundingPath(event.getData(CommonJavaContextKeys.CURRENT_PATH));
        if (surroundingPath == null) {
            return;
        }
        if (surroundingPath.getLeaf() instanceof LambdaExpressionTree) {
            return;
        }
        if (surroundingPath.getLeaf() instanceof TryTree) {
            return;
        }

        presentation.setEnabled(true);
        presentation.setVisible(true);
        presentation.setText(event.getDataContext().getString(R.string.menu_quickfix_surround_try_catch_title));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        File file = e.getRequiredData(CommonDataKeys.FILE);
        JavaCompilerService compiler = e.getRequiredData(CommonJavaContextKeys.COMPILER);
        Diagnostic<?> diagnostic = e.getRequiredData(CommonDataKeys.DIAGNOSTIC);
        TreePath currentPath = e.getRequiredData(CommonJavaContextKeys.CURRENT_PATH);
        TreePath surroundingPath = ActionUtil.findSurroundingPath(currentPath);
        String exceptionName =
                DiagnosticUtil.extractExceptionName(diagnostic.getMessage(Locale.ENGLISH));

        if (surroundingPath == null) {
            return;
        }

        ThreadUtil.runOnBackgroundThread(() -> {
            JavaRewrite r = performInternal(file, exceptionName, surroundingPath);
            RewriteUtil.performRewrite(editor, file, compiler, r);
        });
    }

    private JavaRewrite performInternal(File file, String exceptionName, TreePath surroundingPath) {
        Tree leaf = surroundingPath.getLeaf();
        JCTree tree = (JCTree) leaf;

        CompilationUnitTree root = surroundingPath.getCompilationUnit();
        JCTree.JCCompilationUnit compilationUnit = (JCTree.JCCompilationUnit) root;
        EndPosTable endPositions = compilationUnit.endPositions;

        int start = tree.getStartPosition();
        int end = tree.getEndPosition(endPositions);

        String contents = leaf.toString();
        return new AddTryCatch(file.toPath(), contents, start, end, exceptionName);
    }

}
