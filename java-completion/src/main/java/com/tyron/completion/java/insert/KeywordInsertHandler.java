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
    protected void insert(String string, Editor editor, boolean calcSpace) {
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
                super.insert(textToInsert, editor, false);
                return;
            }
        }

        deletePrefix(editor);
        super.insert(string, editor, true);
    }
}
