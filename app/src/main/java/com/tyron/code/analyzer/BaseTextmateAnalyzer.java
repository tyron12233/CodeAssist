package com.tyron.code.analyzer;

import android.os.Bundle;

import androidx.annotation.NonNull;

import com.tyron.editor.Editor;

import org.eclipse.tm4e.core.theme.IRawTheme;
import org.eclipse.tm4e.core.theme.Theme;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;

import io.github.rosemoe.sora.langs.textmate.TextMateAnalyzer;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * A text mate analyzer which does not use a TextMateLanguage
 */
public class BaseTextmateAnalyzer extends TextMateAnalyzer {

    private static final Field THEME_FIELD;

    public BaseTextmateAnalyzer(TextMateLanguage language, Editor editor,
                                String grammarName,
                                InputStream grammarIns,
                                Reader languageConfiguration,
                                IRawTheme theme) throws Exception {
        super(language, grammarName, grammarIns,
                languageConfiguration, theme);
    }

    @Override
    public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
        if (!extraArguments.getBoolean("loaded", false)) {
            return;
        }
        super.reset(content, extraArguments);
    }

    public Theme getTheme() {
        try {
            return (Theme) THEME_FIELD.get(this);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    static {
        try {
            THEME_FIELD = TextMateAnalyzer.class.getDeclaredField("theme");
            THEME_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
