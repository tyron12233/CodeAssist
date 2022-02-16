package com.tyron.language.xml;

import android.graphics.drawable.Drawable;

import com.tyron.language.api.Language;
import com.tyron.language.fileTypes.LanguageFileType;

import org.jetbrains.annotations.NotNull;

public class XmlFileType extends LanguageFileType {

    public static final XmlFileType INSTANCE = new XmlFileType();

    private XmlFileType() {
        this(XmlLanguage.INSTANCE);
    }

    protected XmlFileType(@NotNull Language instance) {
        super(instance);
    }

    @Override
    public @NotNull String getName() {
        return "XML";
    }

    @Override
    public @NotNull String getDescription() {
        return "XML Language";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "xml";
    }

    @Override
    public @NotNull Drawable getIcon() {
        return null;
    }
}
