package com.tyron.code.ui.editor.action;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.builder.project.Project;
import com.tyron.code.R;
import com.tyron.completion.java.action.FindCurrentPath;
import com.tyron.completion.java.compiler.Parser;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Editor;

import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.io.File;

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

        presentation.setVisible(true);
        presentation.setText("Select all");
        presentation.setIcon(event.getDataContext().getDrawable(R.drawable.round_select_all_20));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        Project project = e.getRequiredData(CommonDataKeys.PROJECT);
        File file = e.getRequiredData(CommonDataKeys.FILE);
        Parser parser = Parser.parseFile(project, file.toPath());

        FindCurrentPath findCurrentPath = new FindCurrentPath(parser.task);

        int cursorStart = editor.getCaret().getStart();
        int cursorEnd = editor.getCaret().getEnd();

        SourcePositions positions = Trees.instance(parser.task).getSourcePositions();
        TreePath path = findCurrentPath.scan(parser.root, cursorStart, cursorEnd);
        if (path != null) {
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
}
