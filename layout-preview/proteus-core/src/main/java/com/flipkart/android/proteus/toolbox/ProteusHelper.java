package com.flipkart.android.proteus.toolbox;

import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.value.Array;
import com.flipkart.android.proteus.value.Layout;

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
