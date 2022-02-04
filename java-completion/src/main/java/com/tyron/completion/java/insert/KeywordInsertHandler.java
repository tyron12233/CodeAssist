package com.tyron.completion.java.insert;

import com.tyron.completion.DefaultInsertHandler;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.util.TreeUtil;
import com.tyron.completion.model.CompletionItem;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.source.tree.BlockTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

public class KeywordInsertHandler extends DefaultInsertHandler {

    private final CompileTask task;
    private final TreePath currentPath;

    public KeywordInsertHandler(CompileTask task, TreePath currentPath, CompletionItem item) {
        super(item);
        this.task = task;
        this.currentPath = currentPath;
    }

    @Override
    protected void insert(String string, Editor editor) {
        Caret caret = editor.getCaret();
        int line = caret.getStartLine();
        int column = caret.getStartColumn();

        if ("return".equals(string)) {
            TreePath method = TreeUtil.findParentOfType(currentPath, MethodTree.class);
            if (method != null) {
                MethodTree methodTree = (MethodTree) method.getLeaf();
                String textToInsert = "return";
                if (TreeUtil.isVoid(methodTree)) {
                    textToInsert = "return;";
                } else if (isEndOfLine(line, column, editor)) {
                    textToInsert = "return ";
                }
                insert(textToInsert, editor);
                return;
            }
        }

        super.insert(string, editor);
    }
    private boolean isEndOfLine(int line, int column, Editor editor) {
        String lineString = editor.getContent().getLineString(line);
        String substring = lineString.substring(column);
        return substring.trim().isEmpty();
    }
}
