package com.tyron.code.ui.theme;

import com.tyron.code.ui.settings.EditorSettingsFragment;
import com.tyron.common.ApplicationProvider;

import java.util.HashMap;
import java.util.Map;

import io.github.rosemoe.sora.langs.textmate.theme.TextMateColorScheme;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import io.github.rosemoe.sora2.text.EditorUtil;

public class ThemeRepository {

    public static final String DEFAULT_LIGHT = "code_assist_default_light";
    public static final String DEFAULT_NIGHT = "code_assist_default_night";

    private static final Map<String, TextMateColorScheme> sSchemeCache = new HashMap<>();

    public static void putColorScheme(String key, TextMateColorScheme scheme) {
        sSchemeCache.put(key, scheme);
    }

    public static TextMateColorScheme getColorScheme(String key) {
        return sSchemeCache.get(key);
    }

    public static TextMateColorScheme getDefaultScheme(boolean light) {
        return EditorUtil.getDefaultColorScheme(ApplicationProvider.getApplicationContext(), light);
    }
}
