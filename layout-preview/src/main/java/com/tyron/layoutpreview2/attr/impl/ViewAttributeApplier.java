package com.tyron.layoutpreview2.attr.impl;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.value.Dimension;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.layoutpreview2.attr.BaseAttributeApplier;
import com.tyron.layoutpreview2.util.AttributeUtils;

public class ViewAttributeApplier extends BaseAttributeApplier {
    @Override
    public boolean accept(@NonNull View view) {
        return true;
    }


    @Override
    public void registerAttributeProcessors() {
        registerAttributeProcessor(ResourceNamespace.ANDROID, "layout_width", (view, attribute) -> {
            if (view.getLayoutParams() == null) {
                // the view does not have a parent
                return;
            }
            final String attributeValue = attribute.getValue();
            if ("wrap_content".equals(attributeValue)) {
                view.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
            } else if ("match_parent".equals(attributeValue)) {
                view.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
            } else if ("fill_parent".equals(attributeValue)) {
                view.getLayoutParams().width = ViewGroup.LayoutParams.FILL_PARENT;
            } else {
                final String resolved = AttributeUtils.resolve(view, attribute);
                final Dimension dimension = Dimension.valueOf(resolved);
                final float apply = dimension.apply(view.getContext());
                view.getLayoutParams().width = (int) apply;
            }
        });

        registerAttributeProcessor(ResourceNamespace.ANDROID, "layout_height", (view, attribute) -> {
            if (view.getLayoutParams() == null) {
                // the view does not have a parent
                return;
            }
            final String attributeValue = attribute.getValue();
            if ("wrap_content".equals(attributeValue)) {
                view.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            } else if ("match_parent".equals(attributeValue)) {
                view.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
            } else if ("fill_parent".equals(attributeValue)) {
                view.getLayoutParams().height = ViewGroup.LayoutParams.FILL_PARENT;
            } else {
                final String resolved = AttributeUtils.resolve(view, attribute);
                final Dimension dimension = Dimension.valueOf(resolved);
                final float apply = dimension.apply(view.getContext());
                view.getLayoutParams().height = (int) apply;
            }
        });
    }
}
