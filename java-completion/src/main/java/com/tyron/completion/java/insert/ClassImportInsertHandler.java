package com.tyron.completion.java.insert;

import com.tyron.completion.DefaultInsertHandler;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.rewrite.AddImport;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.util.RewriteUtil;
import com.tyron.editor.Editor;

import java.io.File;

public class ClassImportInsertHandler extends DefaultInsertHandler {

    protected final File file;
    private final JavaCompilerService service;

    public ClassImportInsertHandler(JavaCompilerService provider, File file, CompletionItem item) {
        super(item);
        this.file = file;
        this.service = provider;
    }

    @Override
    public void handleInsert(Editor editor) {
        super.handleInsert(editor);

        AddImport addImport = new AddImport(file, item.data);
        RewriteUtil.performRewrite(editor, file, service, addImport);
    }
}
