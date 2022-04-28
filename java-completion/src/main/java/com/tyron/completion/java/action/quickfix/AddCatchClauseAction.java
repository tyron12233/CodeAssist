package com.tyron.completion.java.action.quickfix;

import androidx.annotation.NonNull;

import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.R;
import com.tyron.completion.java.action.CommonJavaContextKeys;
import com.tyron.completion.util.RewriteUtil;
import com.tyron.completion.java.rewrite.AddCatchClause;
import com.tyron.completion.java.rewrite.JavaRewrite;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.java.util.DiagnosticUtil;
import com.tyron.common.util.ThreadUtil;
import com.tyron.editor.Editor;

import javax.tools.Diagnostic;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.TryTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree;

import java.io.File;
import java.util.Locale;

public class AddCatchClauseAction extends ExceptionsQuickFix {

    public static final String ID = "javaAddCatchClauseQuickFix";

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

        TreePath surroundingPath = ActionUtil.findSurroundingPath(event.getData(CommonJavaContextKeys.CURRENT_PATH));
        if (surroundingPath == null) {
            return;
        }

        if (!(surroundingPath.getLeaf() instanceof TryTree)) {
            return;
        }

        presentation.setEnabled(true);
        presentation.setVisible(true);
        presentation.setText(event.getDataContext().getString(R.string.menu_quickfix_add_catch_clause_title));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        File file = e.getData(CommonDataKeys.FILE);
        JavaCompilerService compiler = e.getData(CommonJavaContextKeys.COMPILER);
        Diagnostic<?> diagnostic = e.getData(CommonDataKeys.DIAGNOSTIC);
        TreePath currentPath = e.getData(CommonJavaContextKeys.CURRENT_PATH);
        TreePath surroundingPath = ActionUtil.findSurroundingPath(currentPath);
        String exceptionName = DiagnosticUtil.extractExceptionName(diagnostic.getMessage(Locale.ENGLISH));

        if (surroundingPath == null) {
            return;
        }

        ThreadUtil.runOnBackgroundThread(() -> {
            JavaRewrite r = performInternal(file, exceptionName, surroundingPath);
            RewriteUtil.performRewrite(editor, file, compiler, r);
        });
    }

    private JavaRewrite performInternal(File file, String exceptionName, TreePath surroundingPath) {
        CompilationUnitTree root = surroundingPath.getCompilationUnit();
        JCTree.JCCompilationUnit compilationUnit = (JCTree.JCCompilationUnit) root;
        EndPosTable endPositions = compilationUnit.endPositions;


        TryTree tryTree = (TryTree) surroundingPath.getLeaf();
        CatchTree catchTree = tryTree.getCatches().get(tryTree.getCatches().size() - 1);
        JCTree.JCCatch jcCatch = (JCTree.JCCatch) catchTree;

        int start = (int) jcCatch.getEndPosition(endPositions);
        return new AddCatchClause(file.toPath(), start, exceptionName);
    }
}
