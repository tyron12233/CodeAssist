package com.flipkart.android.proteus.toolbox;

import android.view.View;

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
        Array array = attribute.value.getAsArray();
        array.add(child.getViewManager().getLayout());
    }

    public static void removeChildFromLayout(ProteusView parent, ProteusView child) {
        Layout.Attribute attribute = getChildrenAttribute(parent);
        if (attribute == null) {
            return;
        }
        Array array = attribute.value.getAsArray();
        array.remove(child.getViewManager().getLayout());
    }

    public static String getAttributeName(ProteusContext context, String layoutType, int id) {
        ViewTypeParser<View> parser = context.getParser(layoutType);
        if (parser == null) {
            return "Unknown";
        }
        return getAttributeName(parser.getAttributeSet(), id);
    }

    private static String getAttributeName(ViewTypeParser.AttributeSet attrs, int id) {
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
