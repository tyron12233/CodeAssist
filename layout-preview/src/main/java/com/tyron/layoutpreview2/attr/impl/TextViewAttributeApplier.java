package com.tyron.layoutpreview2.attr.impl;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.layoutpreview2.attr.BaseAttributeApplier;

public class TextViewAttributeApplier extends BaseAttributeApplier {
    @Override
    public boolean accept(@NonNull View view) {
        return view instanceof TextView;
    }

    @Override
    public void registerAttributeProcessors() {
        registerStringAttributeProcessor(ResourceNamespace.ANDROID, "text", TextView.class,
                                         TextView::setText);
    }
}
