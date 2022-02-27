package com.tyron.layoutpreview2.attr.impl;

import android.view.View;

import androidx.annotation.NonNull;

import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.layoutpreview2.attr.AttributeApplier;
import com.tyron.layoutpreview2.attr.BaseAttributeApplier;

import org.eclipse.lemminx.dom.DOMAttr;

public class ViewAttributeApplier extends BaseAttributeApplier {
    @Override
    public boolean accept(@NonNull View view) {
        return true;
    }


    @Override
    public void registerAttributeProcessors() {
//        registerStringAttributeProcessor(ResourceNamespace.ANDROID, "layout_width");
    }
}
