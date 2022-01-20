package com.tyron.completion.java.action.quickfix;

import static com.tyron.completion.java.util.DiagnosticUtil.findMethod;

import androidx.annotation.NonNull;

import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.CompilerContainer;
import com.tyron.completion.java.JavaCompilerService;
import com.tyron.completion.java.R;
import com.tyron.completion.java.action.CommonJavaContextKeys;
import com.tyron.completion.java.action.util.RewriteUtil;
import com.tyron.completion.java.rewrite.AddCatchClause;
import com.tyron.completion.java.rewrite.AddException;
import com.tyron.completion.java.rewrite.Rewrite;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.java.util.DiagnosticUtil;
import com.tyron.completion.java.util.ElementUtil;
import com.tyron.completion.java.util.ThreadUtil;
import com.tyron.editor.Editor;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.source.tree.CatchTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.LambdaExpressionTree;
import org.openjdk.source.tree.TryTree;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.tree.EndPosTable;
import org.openjdk.tools.javac.tree.JCTree;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

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
            Rewrite r = performInternal(file, exceptionName, surroundingPath);
            RewriteUtil.performRewrite(editor, file, compiler, r);
        });
    }

    private Rewrite performInternal(File file, String exceptionName, TreePath surroundingPath) {
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
