package com.tyron.layoutpreview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;

public class PreviewLayoutInflater extends LayoutInflater {

    private static final String[] sClassPrefixList = {
            "android.widget.",
            "android.webkit.",
            "android.app."
    };

    public PreviewLayoutInflater(PreviewContext context) {
        super(context);
    }

    @Override
    public LayoutInflater cloneInContext(Context context) {
        if (!(context instanceof PreviewContext)) {
            return null;
        }
        return new PreviewLayoutInflater((PreviewContext) context);
    }

    @Override protected View onCreateView(String name, AttributeSet attrs) throws ClassNotFoundException {
        for (String prefix : sClassPrefixList) {
            try {
                View view = createView(name, prefix, attrs);
                if (view != null) {
                    return view;
                }
            } catch (ClassNotFoundException e) {
                // In this case we want to let the base class take a crack
                // at it.
            }
        }
        return super.onCreateView(name, attrs);
    }

}
