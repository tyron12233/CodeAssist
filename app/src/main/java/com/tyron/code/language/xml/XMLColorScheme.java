package com.tyron.code.language.xml;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

public class XMLColorScheme extends EditorColorScheme {

    public XMLColorScheme() {
        super();
    }

    @Override
    public void applyDefault() {
        for (int i = START_COLOR_ID; i <= END_COLOR_ID; i++) {
            applyDefault(i);
        }
    }

    private void applyDefault(int color) {
        super.applyDefault();
    }
}
