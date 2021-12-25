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
        Map<Path, TextEdit[]> map = new TreeMap<>();
        String newContents = insertColon(contents);
        String edit =
                "try {\n" + newContents + "\n} catch (" + ActionUtil.getSimpleName(exceptionName) +
                        " e) { }";
        Range deleteRange = new Range(start, end);
        TextEdit delete = new TextEdit(deleteRange, "");
        Range range = new Range(start, start);
        TextEdit insert = new TextEdit(range, edit, true);
        map.put(file, new TextEdit[]{delete, insert});

        ParseTask task = compiler.parse(file);
        if (!ActionUtil.hasImport(task.root, exceptionName)) {
            AddImport addImport = new AddImport(file.toFile(), exceptionName);
            Map<Path, TextEdit[]> rewrite = addImport.rewrite(compiler);
            map.putAll(rewrite);
        }
        return map;
    }

    private String insertColon(String string) {
        if (string.endsWith(")")) {
            return string += ";";
        }
        return string;
    }
}
