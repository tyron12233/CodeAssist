package com.tyron.completion.java.rewrite;

import com.google.common.collect.ImmutableMap;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import java.nio.file.Path;
import java.util.Map;

public class AddTryCatch implements Rewrite {

    private final Path file;
    private final String contents;
    private final int start;
    private final int end;
    private final String exceptionName;

    public AddTryCatch(Path file, String contents, int start, int end, String exceptionName) {
        this.file = file;
        this.contents = contents;
        this.start = start;
        this.end = end;
        this.exceptionName = exceptionName;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        String finalString = "try {\n" + contents + "\n} catch (" +
                exceptionName + " e) { }" ;
        Range deleteRange = new Range(start, end);
        TextEdit delete = new TextEdit(deleteRange, "");

        Range range = new Range(start, start);
        return ImmutableMap.of(file, new TextEdit[]{delete, new TextEdit(range,finalString)});
    }
}
