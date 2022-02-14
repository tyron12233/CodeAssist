package com.tyron.completion.util;

import com.tyron.common.util.ThreadUtil;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.Rewrite;
import com.tyron.completion.model.TextEdit;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Editor;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

public class RewriteUtil {

    public static <T> void performRewrite(Editor editor, File file, T neededClass, Rewrite<T> rewrite) {
        ProgressManager.getInstance().runNonCancelableAsync(() -> {
            Map<Path, TextEdit[]> rewrites = rewrite.rewrite(neededClass);
            ProgressManager.getInstance().runLater(() -> {
                editor.beginBatchEdit();
                rewrites.forEach((k, v) -> {
                    if (k.equals(file.toPath())) {
                        for (TextEdit edit : v) {
                            applyTextEdit(editor, edit);
                        }
                    }
                });
                editor.endBatchEdit();
            });
        });
    }

    public static void applyTextEdit(Editor editor, TextEdit edit) {
        int startFormat;
        int endFormat;
        Range range = edit.range;
        if (range.start.line == -1 && range.start.column == -1 || (range.end.line == -1 && range.end.column == -1)) {
            CharPosition startChar =
                    editor.getCharPosition((int) range.start.start);
            CharPosition endChar =
                    editor.getCharPosition((int) range.end.end);

            if (range.start.start == range.end.end) {
                editor.insert(startChar.getLine(), startChar.getColumn(), edit.newText);
            } else {
                editor.replace(startChar.getLine(), startChar.getColumn(), endChar.getLine(),
                        endChar.getColumn(), edit.newText);
            }

            startFormat = (int) range.start.start;
        } else {
            if (range.start.equals(range.end)) {
                editor.insert(range.start.line, range.start.column, edit.newText);
            } else {
                editor.replace(range.start.line, range.start.column, range.end.line,
                        range.end.column, edit.newText);
            }
            startFormat = editor.getCharIndex(range.start.line, range.start.column);
        }
        endFormat = startFormat + edit.newText.length();

        if (startFormat < endFormat) {
            if (edit.needFormat) {
                editor.formatCodeAsync(startFormat, endFormat);
            }
        }
    }
}
