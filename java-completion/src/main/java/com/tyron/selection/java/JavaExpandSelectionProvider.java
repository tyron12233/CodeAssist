package com.tyron.selection.java;

import com.google.common.collect.Range;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.project.Project;
import com.tyron.completion.java.action.FindCurrentPath;
import com.tyron.completion.java.compiler.Parser;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Editor;
import com.tyron.editor.selection.ExpandSelectionProvider;

import org.jetbrains.annotations.Nullable;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.io.File;
import java.time.Instant;

public class JavaExpandSelectionProvider extends ExpandSelectionProvider {

    @Override
    public @Nullable Range<Integer> expandSelection(Editor editor) {
        Project project = editor.getProject();
        if (project == null) {
            return null;
        }
        File file = editor.getCurrentFile();
        SourceFileObject fileObject = new SourceFileObject(file.toPath(), editor.getContent()
                .toString(), Instant.now());
        Parser parser = Parser.parseJavaFileObject(project, fileObject);

        FindCurrentPath findCurrentPath = new FindCurrentPath(parser.task);

        int cursorStart = editor.getCaret().getStart();
        int cursorEnd = editor.getCaret().getEnd();

        SourcePositions positions = Trees.instance(parser.task).getSourcePositions();
        TreePath path = findCurrentPath.scan(parser.root, cursorStart, cursorEnd);
        if (path == null) {
            return null;
        }

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
        return Range.closed((int) afterStart, (int) afterEnd);
    }
}
