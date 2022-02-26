package com.tyron.layoutpreview2;

import android.content.Context;
import android.view.View;

import com.tyron.completion.xml.repository.Repository;

import org.eclipse.lemminx.dom.DOMAttr;

import java.util.List;

public class PhoneLayoutInflater extends EditorInflater {

    private static final String[] sClassPrefixList = {
            "android.widget.",
            "android.webkit."
    };

    public PhoneLayoutInflater(Context context, Repository repository) {
        super(context, repository);
    }

    @Override
    protected View onCreateView(String name, List<DOMAttr> attrs) throws ClassNotFoundException {
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
