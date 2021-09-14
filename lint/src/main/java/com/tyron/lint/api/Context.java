package com.tyron.lint.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class Context {

    public final File file;

    private String contents;

    private Map<String, Object> mProperties;

    private Boolean mContainsCommentSuppress;

    public Context(File file) {
        this.file = file;
    }

    public void setProperty(@NonNull String name, @Nullable Object value) {
        if (value == null) {
            if (mProperties != null) {
                mProperties.remove(name);
            }
        } else {
            if (mProperties == null) {
                mProperties = new HashMap<String, Object>();
            }
            mProperties.put(name, value);
        }
    }
}
