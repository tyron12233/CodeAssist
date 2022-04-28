package com.tyron.completion.java.insert;

import com.tyron.builder.model.SourceFileObject;
import com.tyron.completion.DefaultInsertHandler;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.compiler.ParseTask;
import com.tyron.completion.java.compiler.Parser;
import com.tyron.completion.java.rewrite.AddImport;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.TextEdit;
import com.tyron.completion.util.RewriteUtil;
import com.tyron.editor.Editor;

import java.io.File;
import java.time.Instant;
import java.util.Map;

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
        Parser parse = Parser.parseJavaFileObject(service.getProject(), new SourceFileObject(file.toPath(),
                editor.getContent().toString(), Instant.now()));
        Map<File, TextEdit> imports = addImport.getText(new ParseTask(parse.task, parse.root));
        for (Map.Entry<File, TextEdit> entry : imports.entrySet()) {
            RewriteUtil.applyTextEdit(editor, entry.getValue());
        }
    }
}
