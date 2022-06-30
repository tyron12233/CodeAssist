package com.tyron.layoutpreview2.attr;

import android.view.View;

import androidx.annotation.NonNull;

import org.eclipse.lemminx.dom.DOMAttr;

public interface AttributeApplier {

    boolean accept(@NonNull View view);

    void apply(@NonNull View view, @NonNull DOMAttr attr);
}
