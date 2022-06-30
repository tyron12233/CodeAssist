package com.tyron.language.xml;

import com.tyron.language.api.Language;

import org.jetbrains.annotations.NotNull;

public class XmlLanguage extends Language {

    public static final XmlLanguage INSTANCE = new XmlLanguage("XML");

    protected XmlLanguage(@NotNull String id) {
        super(id);
    }
}
