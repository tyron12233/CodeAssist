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

    @Override
    public void onApplyColorScheme(EditorColorScheme colorScheme) {
        super.onApplyColorScheme(colorScheme);
    }

    @Override
    public View inflate(Context context) {
        return super.inflate(context);
    }
}
