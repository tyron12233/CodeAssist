package com.tyron.layoutpreview2.attr.impl;

import android.view.View;

import androidx.annotation.NonNull;

import com.tyron.layoutpreview2.attr.AttributeApplier;

import org.eclipse.lemminx.dom.DOMAttr;

public class ViewAttributeApplier implements AttributeApplier {
    @Override
    public boolean accept(@NonNull View view) {
        return true;
    }

    @Override
    public void apply(@NonNull View view, @NonNull DOMAttr attr) {

    }
}
