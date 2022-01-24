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

public class AddTryCatch implements JavaRewrite {

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
        List<TextEdit> edits = new ArrayList<>();
        String newContents = insertColon(contents);
        String edit =
                "try {\n" + newContents + "\n} catch (" + ActionUtil.getSimpleName(exceptionName) +
                        " e) { }";
        Range deleteRange = new Range(start, end);
        TextEdit delete = new TextEdit(deleteRange, "");
        edits.add(delete);

        Range range = new Range(start, start);
        TextEdit insert = new TextEdit(range, edit, true);
        edits.add(insert);

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

    private String insertColon(String string) {
        if (string.endsWith(")")) {
            return string += ";";
        }
        return string;
    }
}
