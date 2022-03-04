package io.github.rosemoe.sora2.text;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.Collection;

import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.langs.textmate.theme.TextMateColorScheme;
import io.github.rosemoe.sora.text.ContentLine;
import io.github.rosemoe.sora.text.ICUUtils;
import io.github.rosemoe.sora.textmate.core.internal.theme.ThemeRaw;
import io.github.rosemoe.sora.textmate.core.internal.theme.reader.ThemeReader;
import io.github.rosemoe.sora.textmate.core.theme.IRawTheme;
import io.github.rosemoe.sora.textmate.core.theme.IRawThemeSetting;
import io.github.rosemoe.sora.util.IntPair;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

public class EditorUtil {

    public static final String KEY_BACKGROUND = "background";
    public static final String KEY_BLOCK_LINE = "blockLineColor";
    public static final String KEY_CURRENT_BLOCK_LINE = "currentBlockLineColor";
    public static final String KEY_COMPLETION_WINDOW_BACKGROUND = "completionWindowBackground";
    public static final String KEY_COMPLETION_WINDOW_STROKE = "completionWindowStroke";

    @NonNull
    public static TextMateColorScheme createTheme(IRawTheme rawTheme) {
        TextMateColorScheme scheme = TextMateColorScheme.create(rawTheme);
        Collection<IRawThemeSetting> settings = rawTheme.getSettings();
        if (settings != null && settings.size() >= 1) {
            ThemeRaw setting = (ThemeRaw) settings.iterator().next();
            setting = (ThemeRaw) setting.getSetting();

            Object blockLine = setting.get(KEY_BLOCK_LINE);
            if (blockLine != null) {
                scheme.setColor(EditorColorScheme.BLOCK_LINE, getColor(blockLine));
            }

            Object currBlockLine = setting.get(KEY_CURRENT_BLOCK_LINE);
            if (currBlockLine == null) {
                currBlockLine = blockLine;
            }
            if (currBlockLine != null) {
                scheme.setColor(EditorColorScheme.BLOCK_LINE_CURRENT, getColor(currBlockLine));
            }

            Object completionWindowBackground = setting.get(KEY_COMPLETION_WINDOW_BACKGROUND);
            if (completionWindowBackground == null) {
                completionWindowBackground = setting.get(KEY_BACKGROUND);
            }
            scheme.setColor(EditorColorScheme.AUTO_COMP_PANEL_BG,
                            getColor(completionWindowBackground));

            Object completionStroke = setting.get(KEY_COMPLETION_WINDOW_STROKE);
            scheme.setColor(EditorColorScheme.AUTO_COMP_PANEL_CORNER,
                            getColor(completionStroke, Color.TRANSPARENT));
        }
        return scheme;
    }


    private static int getColor(@Nullable Object color) {
        return getColor(color, Color.WHITE);
    }

    private static int getColor(@Nullable Object color, @ColorInt int def) {
        if (!(color instanceof String)) {
            return def;
        }
        try {
            return Color.parseColor((String) color);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    public static boolean isDarkMode(Context context) {
        int uiMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static TextMateColorScheme getDefaultColorScheme(Context context) {
        try {
            boolean darkMode = isDarkMode(context);
            if (darkMode) {
                return getDefaultColorScheme(context, false);
            } else {
                return getDefaultColorScheme(context, true);
            }
        } catch (Exception e) {
            // should not happen, the bundled theme should always work.
            throw new Error(e);
        }
    }

    public static TextMateColorScheme getDefaultColorScheme(Context context, boolean light) {
        try {
            AssetManager assets = context.getAssets();
            IRawTheme rawTheme;
            if (light) {
                rawTheme = ThemeReader.readThemeSync("QuietLight.tmTheme", assets.open(
                        "textmate/QuietLight.tmTheme"));
            } else {
                rawTheme = ThemeReader.readThemeSync("darcula.json",
                                                     assets.open("textmate/darcula.json"));
            }
            return createTheme(rawTheme);
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
