package com.tyron.completion.util;

import android.os.Looper;

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.tyron.common.ApplicationProvider;
import com.tyron.common.logging.IdeLog;
import com.tyron.common.util.ThreadUtil;
import com.tyron.completion.model.Position;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.Rewrite;
import com.tyron.completion.model.TextEdit;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Editor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import kotlin.ReplaceWith;

public class RewriteUtil {

    private static final Logger sLogger = IdeLog.getCurrentLogger(RewriteUtil.class);

    public static <T> void performRewrite(Editor editor, File file, T neededClass, Rewrite<T> rewrite) {
        ListenableFuture<Map<Path, TextEdit[]>> future = ProgressManager.getInstance()
                .computeNonCancelableAsync(
                        () -> {
                            Map<Path, TextEdit[]> edits = rewrite.rewrite(neededClass);
                            if (edits == Rewrite.CANCELLED) {
                                throw new RuntimeException("Rewrite cancelled.");
                            }
                            return Futures.immediateFuture(edits);
                        });
        Futures.addCallback(future, new FutureCallback<Map<Path, TextEdit[]>>() {
            @Override
            public void onSuccess(@Nullable Map<Path, TextEdit[]> result) {
                if (result == null) {
                    return;
                }
                TextEdit[] textEdits = result.get(file.toPath());
                if (textEdits == null) {
                    return;
                }
                editor.beginBatchEdit();
                for (TextEdit textEdit : textEdits) {
                    applyTextEdit(editor, textEdit);
                }
                editor.endBatchEdit();
            }

            @Override
            public void onFailure(Throwable t) {
                // TODO: Handle errors
                sLogger.severe("Failed to perform rewrite, " + Throwables.getStackTraceAsString(t));
            }
        }, ThreadUtil::runOnUiThread);
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
