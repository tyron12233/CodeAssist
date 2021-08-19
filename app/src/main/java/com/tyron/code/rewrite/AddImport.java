package com.tyron.code.rewrite;
import com.tyron.code.model.Position;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.JavacTask;
import com.sun.source.tree.Tree;
import com.sun.source.util.Trees;
import com.tyron.code.ParseTask;
import java.util.List;
import com.sun.source.tree.ImportTree;
import java.util.Map;
import com.tyron.code.model.TextEdit;
import java.io.File;

public class AddImport {
    
    private String className;
    private File currentFile;
    
    public AddImport(File currentFile, String className) {
        this.className = className;
        this.currentFile = currentFile;
    }
    
    public Map<File, TextEdit> getText(ParseTask task) {
        Position point = insertPosition(task);
        String text = "import " + className + ";\n";
        TextEdit edit = new TextEdit(point, point, text);
        return Map.of(currentFile, edit);
    }
    
    private Position insertPosition(ParseTask task) {
        List<? extends ImportTree> imports = task.root.getImports();
        for (ImportTree i : imports) {
            String next = i.getQualifiedIdentifier().toString();
            if (className.compareTo(next) < 0) {
                return insertBefore(task, i);
            }
        }
        if (!imports.isEmpty()) {
            ImportTree last = imports.get(imports.size() - 1);
            return insertAfter(task, last);
        }
        if (task.root.getPackageName() != null) {
            return insertAfter(task, task.root.getPackageName());
        }
        return new Position(0, 0);
    }

    private Position insertBefore(ParseTask task, Tree i) {
        SourcePositions pos = Trees.instance(task.task).getSourcePositions();
        long offset = pos.getStartPosition(task.root, i);
        int line = (int) task.root.getLineMap().getLineNumber(offset);
        return new Position(line - 1, 0);
    }

    private Position insertAfter(ParseTask task, Tree i) {
        SourcePositions pos = Trees.instance(task.task).getSourcePositions();
        long offset = pos.getStartPosition(task.root, i);
        int line = (int) task.root.getLineMap().getLineNumber(offset);
        return new Position(line, 0);
    }
}
