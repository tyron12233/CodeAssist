package com.tyron.completion.java.insert;

import com.tyron.completion.DefaultInsertHandler;
import com.tyron.completion.java.provider.JavacUtilitiesProvider;
import com.tyron.completion.java.rewrite.AddImport;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.TextEdit;
import com.tyron.completion.util.RewriteUtil;
import com.tyron.editor.Editor;

import java.io.File;
import java.util.Map;

/**
 * Handles the proper insertion of import declarations when a class name has been selected
 * from the completion list.
 */
public class ClassImportInsertHandler extends DefaultInsertHandler {

    protected final File file;
    private final JavacUtilitiesProvider utils;

    public ClassImportInsertHandler(JavacUtilitiesProvider provider,
                                    File file,
                                    CompletionItem item) {
        super(item);
        this.file = file;
        this.utils = provider;
    }

    @Override
    public void handleInsert(Editor editor) {
        super.handleInsert(editor);

        AddImport addImport = new AddImport(file, item.data);
        Map<File, TextEdit> imports = addImport.getText(utils);
        for (Map.Entry<File, TextEdit> entry : imports.entrySet()) {
            RewriteUtil.applyTextEdit(editor, entry.getValue());
        }
    }
}
