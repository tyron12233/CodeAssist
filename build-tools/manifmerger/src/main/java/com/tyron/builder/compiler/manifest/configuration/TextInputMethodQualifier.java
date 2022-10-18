package com.tyron.builder.compiler.manifest.configuration;

import com.tyron.builder.compiler.manifest.resources.Keyboard;
import com.tyron.builder.compiler.manifest.resources.ResourceEnum;

/**
 * Resource Qualifier for Text Input Method.
 */
public final class TextInputMethodQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Text Input Method";

    private Keyboard mValue;


    public TextInputMethodQualifier() {
        // pass
    }

    public TextInputMethodQualifier(Keyboard value) {
        mValue = value;
    }

    public Keyboard getValue() {
        return mValue;
    }

    @Override
    public ResourceEnum getEnumValue() {
        return mValue;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getShortName() {
        return "Text Input";
    }

    @Override
    public int since() {
        return 1;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        Keyboard method = Keyboard.getEnum(value);
        if (method != null) {
            TextInputMethodQualifier qualifier = new TextInputMethodQualifier();
            qualifier.mValue = method;
            config.setTextInputMethodQualifier(qualifier);
            return true;
        }

        return false;
    }
}
