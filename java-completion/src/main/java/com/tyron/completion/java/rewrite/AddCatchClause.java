package com.tyron.completion.java.rewrite;

import com.google.common.collect.ImmutableMap;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.java.ParseTask;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

public class AddCatchClause implements Rewrite {

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
        Map<Path, TextEdit[]> map = new TreeMap<>();

        String finalString = " catch (" + ActionUtil.getSimpleName(exceptionName) + " e) { }";
        Range range = new Range(start, start);
        TextEdit edit = new TextEdit(range, finalString, true);
        map.put(file, new TextEdit[]{edit});

        ParseTask task = compiler.parse(file);
        if (!ActionUtil.hasImport(task.root, exceptionName)) {
            AddImport addImport = new AddImport(file.toFile(), exceptionName);
            Map<Path, TextEdit[]> rewrite = addImport.rewrite(compiler);
            map.putAll(rewrite);
        }
        return map;
    }

}
