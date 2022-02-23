package io.github.rosemoe.sora2.text;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;

import java.lang.reflect.Method;

import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.langs.textmate.theme.TextMateColorScheme;
import io.github.rosemoe.sora.text.ContentLine;
import io.github.rosemoe.sora.text.ICUUtils;
import io.github.rosemoe.sora.textmate.core.internal.theme.reader.ThemeReader;
import io.github.rosemoe.sora.textmate.core.theme.IRawTheme;
import io.github.rosemoe.sora.util.IntPair;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

public class EditorUtil {

    public static TextMateColorScheme getDefaultColorScheme(Context context) {
        try {
            AssetManager assets = context.getAssets();
            int uiMode = context.getResources()
                                 .getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            IRawTheme rawTheme;
            switch (uiMode) {
                case Configuration.UI_MODE_NIGHT_YES:
                    rawTheme = ThemeReader.readThemeSync("darcula.json",
                                                         assets.open("textmate/darcula.json"));
                    break;
                default:
                case Configuration.UI_MODE_NIGHT_NO:
                    rawTheme = ThemeReader.readThemeSync("QuietLight.tmTheme", assets.open(
                            "textmate/QuietLight.tmTheme"));
            }
            return TextMateColorScheme.create(rawTheme);
        } catch (Exception e) {
            // should not happen, the bundled theme should always work.
            throw new Error(e);
        }
    }

    public static int getFormatIndent(Language language, String line) {
        Class<? extends Language> aClass = language.getClass();
        try {
            Method getIndentAdvance = aClass.getDeclaredMethod("getFormatIndent", String.class);
            Object indent = getIndentAdvance.invoke(language, line);
            if (indent instanceof Integer) {
                return (int) indent;
            }
        } catch (Throwable e) {
            // ignored
        }
        return 0;
    }

    public static boolean isWhitespace(CharSequence charSequence) {
        for (int i = 0; i < charSequence.length(); i++) {
            char c = charSequence.charAt(i);
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }
    public static void selectWord(CodeEditor editor, int line, int column) {
        // Find word edges
        int startLine = line, endLine = line;
        ContentLine lineObj = editor.getText().getLine(line);
        long edges = ICUUtils.getWordEdges(lineObj, column);
        int startColumn = IntPair.getFirst(edges);
        int endColumn = IntPair.getSecond(edges);
        if (startColumn == endColumn) {
            if (startColumn > 0) {
                startColumn--;
            } else if (endColumn < lineObj.length()) {
                endColumn++;
            } else {
                if (line > 0) {
                    int lastColumn = editor.getText().getColumnCount(line - 1);
                    startLine = line - 1;
                    startColumn = lastColumn;
                } else if (line < editor.getLineCount() - 1) {
                    endLine = line + 1;
                    endColumn = 0;
                }
            }
        }
        editor.setSelectionRegion(startLine, startColumn, endLine, endColumn, SelectionChangeEvent.CAUSE_LONG_PRESS);
    }
}
