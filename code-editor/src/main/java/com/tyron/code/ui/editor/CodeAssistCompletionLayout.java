package com.tyron.code.ui.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.AdapterView;

import java.util.Collection;

import io.github.rosemoe.sora.langs.textmate.theme.TextMateColorScheme;
import io.github.rosemoe.sora.textmate.core.internal.theme.ThemeRaw;
import io.github.rosemoe.sora.textmate.core.theme.IRawTheme;
import io.github.rosemoe.sora.textmate.core.theme.IRawThemeSetting;
import io.github.rosemoe.sora.textmate.core.theme.IThemeSetting;
import io.github.rosemoe.sora.widget.component.CompletionLayout;
import io.github.rosemoe.sora.widget.component.DefaultCompletionLayout;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

public class CodeAssistCompletionLayout extends DefaultCompletionLayout {

    public static final String KEY_COMPLETION_WINDOW_BACKGROUND = "completionWindowBackground";
    public static final String KEY_COMPLETION_WINDOW_STROKE = "completionWindowStroke";

    @Override
    public void onApplyColorScheme(EditorColorScheme colorScheme) {
        if (colorScheme instanceof TextMateColorScheme) {
            TextMateColorScheme tm = ((TextMateColorScheme) colorScheme);
            IRawTheme rawTheme = tm.getRawTheme();
            Collection<IRawThemeSetting> settings = rawTheme.getSettings();
            if (!settings.isEmpty()) {
                IRawThemeSetting rawThemeSetting = settings.iterator()
                        .next();
                ThemeRaw setting = (ThemeRaw) rawThemeSetting.getSetting();
                Object background = setting.get(KEY_COMPLETION_WINDOW_BACKGROUND);
                if (background != null) {
                    colorScheme.setColor(EditorColorScheme.AUTO_COMP_PANEL_BG,
                                         getColor((String) background));
                }

                Object stroke = setting.get(KEY_COMPLETION_WINDOW_STROKE);
                if (stroke != null) {
                    colorScheme.setColor(EditorColorScheme.AUTO_COMP_PANEL_CORNER,
                                         getColor((String) stroke));
                }
            }
        }
        super.onApplyColorScheme(colorScheme);
    }

    @Override
    public View inflate(Context context) {
        return super.inflate(context);
    }

    private static int getColor(String color) {
        try {
            return Color.parseColor(color);
        } catch (IllegalArgumentException e) {
            return Color.WHITE;
        }
    }
}
