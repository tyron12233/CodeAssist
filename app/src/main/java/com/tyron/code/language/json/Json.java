package com.tyron.code.language.json;

import com.tyron.code.language.Language;
import com.tyron.code.language.LanguageManager;
import com.tyron.editor.Editor;

import java.io.File;

public class Json implements Language {
    @Override
    public boolean isApplicable(File ext) {
        return ext.getName().endsWith(".json");
    }

    @Override
    public io.github.rosemoe.sora.lang.Language get(Editor editor) {
        return LanguageManager.createTextMateLanguage("json.tmLanguage.json",
                "textmate/json" + "/syntaxes/json" + ".tmLanguage.json",
                "textmate/json/language-configuration.json", editor);
    }

}