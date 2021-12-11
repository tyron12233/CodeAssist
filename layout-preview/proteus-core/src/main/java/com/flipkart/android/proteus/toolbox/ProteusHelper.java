package com.flipkart.android.proteus.toolbox;

import android.view.View;
import android.view.ViewGroup;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.value.Array;
import com.flipkart.android.proteus.value.Layout;

import java.util.Map;

public class ProteusHelper {

    public static void addChildToLayout(ProteusView parent, ProteusView child) {
        Layout.Attribute attribute = getChildrenAttribute(parent);
        if (attribute == null) {
            return;
        }

        int index = ((ViewGroup) parent).indexOfChild(child.getAsView());
        Array array = attribute.value.getAsArray();
        array.add(index, child.getViewManager().getLayout());
    }

    public static void removeChildFromLayout(ProteusView parent, ProteusView child) {
        Layout.Attribute attribute = getChildrenAttribute(parent);
        if (attribute == null) {
            return;
        }
        Array array = attribute.value.getAsArray();
        array.remove(child.getViewManager().getLayout());
    }

    public static String getAttributeName(ProteusContext context, Layout parentLayout, String layoutType, int id) {
        ViewTypeParser<View> parser = context.getParser(layoutType);
        if (parser != null) {
            return getAttributeName(parser.getAttributeSet(), id);
        }

        if (parentLayout == null) {
            return "Unknown";
        }

        parser = context.getParser(parentLayout.type);
        if (parser != null) {
            return getAttributeName(parser.getAttributeSet(), id);
        }
        return "Unknown";
    }

    public static String getAttributeName(ProteusView view, String layoutType, int id) {
        if (view == null) {
            return "Unknown";
        }
        ViewTypeParser<View> parser = view.getViewManager()
                .getContext()
                .getParser(layoutType);
        if (parser == null) {
            return getAttributeName((ProteusView) view.getAsView().getParent(),
                    layoutType, id);
        }
        return getAttributeName(parser.getAttributeSet(), id);
    }

    private static String getAttributeName(ViewTypeParser.AttributeSet attrs, int id) {
        for (Map.Entry<String, ViewTypeParser.AttributeSet.Attribute> entry : attrs.getLayoutParamsAttributes().entrySet()) {
            String k = entry.getKey();
            ViewTypeParser.AttributeSet.Attribute v = entry.getValue();
            if (v.id == id) {
                return k;
            }
        }

        for (Map.Entry<String, ViewTypeParser.AttributeSet.Attribute> entry : attrs.getAttributes().entrySet()) {
            String k = entry.getKey();
            ViewTypeParser.AttributeSet.Attribute v = entry.getValue();
            if (v.id == id) {
                return k;
            }
        }
        return "Unknown";
    }

    private static Layout.Attribute getChildrenAttribute(ProteusView view) {
        if (view.getViewManager().getLayout().attributes == null) {
            return null;
        }
        ViewTypeParser.AttributeSet.Attribute attribute = view.getViewManager()
                .getAvailableAttributes()
                .get("children");
        if (attribute == null) {
            return null;
        }

        for (Layout.Attribute attr : view.getViewManager().getLayout().attributes) {
            if (attribute.id == attr.id) {
                return attr;
            }
        }
        return null;
    }
}
