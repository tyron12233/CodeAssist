package com.tyron.completion.java.rewrite;

import com.google.common.collect.ImmutableMap;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.java.compiler.ParseTask;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class AddCatchClause implements JavaRewrite {

    private final Path file;
    private final int start;
    private final String exceptionName;

    public AddCatchClause(Path file, int start, String exceptionName) {
        this.file = file;
        this.start = start;
        this.exceptionName = exceptionName;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        List<TextEdit> edits = new ArrayList<>();

        String finalString = " catch (" + ActionUtil.getSimpleName(exceptionName) + " e) { }";
        Range range = new Range(start, start);
        TextEdit edit = new TextEdit(range, finalString, true);
        edits.add(edit);

        ParseTask task = compiler.parse(file);
        if (!ActionUtil.hasImport(task.root, exceptionName)) {
            AddImport addImport = new AddImport(file.toFile(), exceptionName);
            Map<Path, TextEdit[]> rewrite = addImport.rewrite(compiler);
            TextEdit[] imports = rewrite.get(file);
            if (imports != null) {
                Collections.addAll(edits, imports);
            }
        }
        return ImmutableMap.of(file, edits.toArray(new TextEdit[0]));
    }

}
