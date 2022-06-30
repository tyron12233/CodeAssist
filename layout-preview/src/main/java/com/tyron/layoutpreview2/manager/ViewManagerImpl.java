package com.tyron.layoutpreview2.manager;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.google.common.collect.ImmutableSet;
import com.tyron.layoutpreview2.EditorContext;
import com.tyron.layoutpreview2.ViewManager;
import com.tyron.layoutpreview2.attr.AttributeApplier;

import org.eclipse.lemminx.dom.DOMAttr;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ViewManagerImpl implements ViewManager {

    private final View mView;

    private final Set<DOMAttr> mAppliedAttrs = new HashSet<>();

    public ViewManagerImpl(@NonNull View view) {
        mView = view;
    }

    @NonNull
    @Override
    public View getView() {
        return mView;
    }

    @Override
    public void updateAttributes(@NonNull List<DOMAttr> attrs) {
        mAppliedAttrs.clear();

        final EditorContext editorContext = EditorContext.getEditorContext(mView.getContext());
        final ImmutableSet<AttributeApplier> attributeAppliers =
                editorContext.getAttributeAppliers();
        attributeAppliers.forEach(applier -> {
            mAppliedAttrs.addAll(attrs);
            if (applier.accept(getView())) {
                attrs.forEach(attr -> applier.apply(getView(), attr));
            }
        });
    }

    @VisibleForTesting
    public Set<DOMAttr> getAppliedAttrs() {
        return mAppliedAttrs;
    }
}
