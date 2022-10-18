package com.tyron.layoutpreview2;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.xml.completion.repository.Repository;
import com.tyron.layoutpreview2.view.EditorView;

import org.eclipse.lemminx.dom.DOMAttr;

import java.util.List;

/**
 * LayoutInflater subclass that substitutes tags from xml with their android framework equivalent
 *
 * e.g LinearLayout to android.widget.LinearLayout
 */
public class PhoneLayoutInflater extends EditorInflater {

    private static final String[] sClassPrefixList = {
            "android.widget.",
            "android.webkit."
    };

    private final EditorContext mContext;

    public PhoneLayoutInflater(EditorContext context) {
        super(context);

        mContext = context;
    }

    @Override
    protected EditorView onCreateView(String name, List<DOMAttr> attrs) throws ClassNotFoundException {
        for (String prefix : sClassPrefixList) {
            try {
                EditorView view = createView(name, prefix, attrs);
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

    @Nullable
    @Override
    protected String replaceFqn(@NonNull String fqn) {
        return mContext.getMapping(fqn);
    }
}
