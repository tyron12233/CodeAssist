package com.tyron.completion.java.rewrite;

import com.google.common.collect.ImmutableMap;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import java.nio.file.Path;
import java.util.Map;

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
        String finalString = " catch (" + exceptionName + " e) { }";
        Range range = new Range(start, start);
        TextEdit edit = new TextEdit(range, finalString);
        return ImmutableMap.of(file, new TextEdit[]{edit});
    }
}
