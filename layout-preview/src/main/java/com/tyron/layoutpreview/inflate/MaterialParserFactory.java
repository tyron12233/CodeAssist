package com.tyron.layoutpreview.inflate;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusParserFactory;
import com.flipkart.android.proteus.ViewTypeParser;

import java.util.HashMap;
import java.util.Map;

public class MaterialParserFactory implements ProteusParserFactory {

    private static final Map<String, String> sMappings = new HashMap<>();

    static {
        sMappings.put("Button", "com.google.android.material.button.MaterialButton");
        sMappings.put("EditText", "com.google.android.material.textfield.TextInputEditText");
    }

    private final ProteusContext mContext;

    public MaterialParserFactory(ProteusContext context) {
        mContext = context;
    }

    @Nullable
    @Override
    public <T extends View> ViewTypeParser<T> getParser(@NonNull String type) {
        if (type.contains(".")) {
            return null;
        }
        String parser = sMappings.get(type);
        if (parser == null) {
            return null;
        }
        return mContext.getParser(parser);
    }
}
