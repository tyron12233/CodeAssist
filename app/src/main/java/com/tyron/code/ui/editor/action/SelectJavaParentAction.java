package com.tyron.code.ui.editor.action;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.Presentation;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.project.Project;
import com.tyron.code.R;
import com.tyron.completion.java.action.FindCurrentPath;
import com.tyron.completion.java.compiler.Parser;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Editor;

import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.io.File;
import java.time.Instant;

public class SelectJavaParentAction extends AnAction {

    public static final String ID = "selectScopeParent";

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!ActionPlaces.EDITOR.equals(event.getPlace())) {
            return;
        }

        File file = event.getData(CommonDataKeys.FILE);
        if (file == null || !file.getName().endsWith(".java")) {
            return;
        }

        if (event.getData(CommonDataKeys.PROJECT) == null) {
            return;
        }

        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }

        DataContext context = event.getDataContext();
        presentation.setVisible(true);
        presentation.setText(context.getString(R.string.expand_selection));
        presentation.setIcon(ResourcesCompat.getDrawable(context.getResources(),
                R.drawable.ic_baseline_code_24, context.getTheme()));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        Project project = e.getRequiredData(CommonDataKeys.PROJECT);
        File file = e.getRequiredData(CommonDataKeys.FILE);

        SourceFileObject fileObject = new SourceFileObject(file.toPath(),
                editor.getContent().toString(), Instant.now());
        Parser parser = Parser.parseJavaFileObject(project, fileObject);

        FindCurrentPath findCurrentPath = new FindCurrentPath(parser.task);

        int cursorStart = editor.getCaret().getStart();
        int cursorEnd = editor.getCaret().getEnd();

        SourcePositions positions = Trees.instance(parser.task).getSourcePositions();
        TreePath path = findCurrentPath.scan(parser.root, cursorStart, cursorEnd);
        if (path != null) {
            path = modifyTreePath(path);

            long afterStart;
            long afterEnd;

            long currentStart = positions.getStartPosition(parser.root, path.getLeaf());
            long currentEnd = positions.getEndPosition(parser.root, path.getLeaf());
            if (currentStart == cursorStart && currentEnd == cursorEnd) {
                TreePath parentPath = path.getParentPath();
                afterStart = positions.getStartPosition(parser.root, parentPath.getLeaf());
                afterEnd = positions.getEndPosition(parser.root, parentPath.getLeaf());
            } else {
                afterStart = currentStart;
                afterEnd = currentEnd;
            }
            CharPosition start = editor.getCharPosition((int) afterStart);
            CharPosition end = editor.getCharPosition((int) afterEnd);
            editor.setSelectionRegion(start.getLine(), start.getColumn(),
                    end.getLine(), end.getColumn());
        }
    }

    @NonNull
    private TreePath modifyTreePath(TreePath treePath) {
        if (true) {
            return treePath;
        }

        TreePath parent = treePath.getParentPath();

        if (treePath.getLeaf().getKind() == Tree.Kind.BLOCK) {
            // select the parent of { }
            return parent;
        }

        if (treePath.getLeaf().getKind() == Tree.Kind.MEMBER_SELECT) {
            return modifyTreePath(parent);
        }

        if (treePath.getLeaf() instanceof ClassTree
                && parent.getLeaf().getKind() == Tree.Kind.NEW_CLASS) {
            if (parent.getParentPath().getLeaf().getKind() == Tree.Kind.EXPRESSION_STATEMENT) {
                return parent.getParentPath();
            }

            return parent;
        }

        if (treePath.getLeaf().getKind() == Tree.Kind.IDENTIFIER) {
            if (parent.getLeaf().getKind() == Tree.Kind.MEMBER_SELECT) {
                return modifyTreePath(parent);
            }
            if (parent.getLeaf() .getKind() == Tree.Kind.METHOD_INVOCATION) {
                // identifier -> method call -> expression
                return parent.getParentPath();
            }
        }

        if (treePath.getLeaf().getKind() == Tree.Kind.METHOD_INVOCATION) {
            return parent;
        }
        return treePath;
    }
}
