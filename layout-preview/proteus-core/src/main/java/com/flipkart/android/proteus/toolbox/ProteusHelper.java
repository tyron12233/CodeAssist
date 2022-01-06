package com.flipkart.android.proteus.toolbox;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.value.Array;
import com.flipkart.android.proteus.value.Layout;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ProteusHelper {

    /**
     * Sometimes other views such as {@link TextInputLayout} wraps the context, thus making
     * the ProteusContext inaccessible
     *
     * @return the {@link ProteusContext} for a specified view
     */
    public static ProteusContext getProteusContext(View view) {
        if (view instanceof ProteusView) {
            ProteusView.Manager viewManager = ((ProteusView) view).getViewManager();
            if (viewManager != null) {
                return viewManager.getContext();
            }
        }
        Context context = view.getContext();
        if (context instanceof ProteusContext) {
            return (ProteusContext) context;
        }
        if (context instanceof ContextThemeWrapper) {
            return (ProteusContext) ((ContextThemeWrapper) context).getBaseContext();
        }

        throw new IllegalArgumentException("View argument is not using a ProteusContext");
    }

    public static boolean isAttributeFromView(ProteusView view, String name, int id) {
        String attributeName = getAttributeName(view, id, false);
        return name.equals(attributeName);
    }

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

    public static String getAttributeName(ProteusView view, int id, boolean extra) {
        if (view == null) {
            return "Unknown";
        }
        String layoutType = view.getViewManager().getLayout().type;
        String attributeName = "Unknown";
        String attributeNameFromParent = "Unknown";

        if (view.getAsView().getParent() instanceof ProteusView) {
            attributeNameFromParent = getAttributeName((ProteusView) view.getAsView().getParent()
                    , id);
        }

        ViewTypeParser<View> parser = view.getViewManager().getContext().getParser(layoutType);
        if (parser != null) {
            attributeName = getAttributeName(parser.getAttributeSet(), id);
        }

        if (extra && !"Unknown".equals(attributeNameFromParent)) {
            return attributeNameFromParent;
        }

        return attributeName;
    }

    public static String getAttributeName(ProteusContext context, Layout parentLayout,
                                          String layoutType, int id) {
        ViewTypeParser<View> parser = context.getParser(layoutType);
        if (parser != null) {
            return getAttributeName(parser.getAttributeSet(), id);
        }

        if (parentLayout == null) {
            return "Unknown";
        }

        String attributeName = "Unknown";

        parser = context.getParser(parentLayout.type);
        if (parser != null) {
            attributeName = getAttributeName(parser.getAttributeSet(), id);
        }
        if ("Unknown".equals(attributeName)) {
            parser = context.getParser("android.view.View");
            if (parser != null) {
                attributeName = getAttributeName(parser.getAttributeSet(), id);
            }
        }
        return attributeName;
    }

    public static String getAttributeName(ProteusView view, int id) {
        if (view == null) {
            return "Unknown";
        }
        String layoutType = view.getViewManager().getLayout().type;
        String attributeName = "Unknown";
        String attributeNameFromParent = "Unknown";

        if (view.getAsView().getParent() instanceof ProteusView) {
            attributeNameFromParent = getAttributeName((ProteusView) view.getAsView().getParent()
                    , id);
        }

        ViewTypeParser<View> parser = view.getViewManager().getContext().getParser(layoutType);
        if (parser != null) {
            attributeName = getAttributeName(parser.getAttributeSet(), id);
        }

        if ("Unknown".equals(attributeName)) {
            return attributeNameFromParent;
        }

        return attributeName;
    }

    @SuppressWarnings({"unchecked", "CastCanBeRemovedNarrowingVariableType"})
    public static ViewTypeParser<View> getViewTypeParser(@NonNull ProteusView view,
                                                         String attributeName, boolean isExtra) {
        ProteusView.Manager viewManager = view.getViewManager();
        int id = viewManager.getViewTypeParser().getAttributeId(attributeName);
        ViewTypeParser<?> parser = null;
        if (id != -1) {
            parser = viewManager.getViewTypeParser();
        }

        ViewTypeParser<?> parentParser = null;

        if (view.getAsView().getParent() instanceof ProteusView) {
            ProteusView parent = (ProteusView) view.getAsView().getParent();
            parentParser = parent.getViewManager().getViewTypeParser();
        }

        if (parser != null && parentParser != null) {
            if (isExtra) {
                //noinspection unchecked
                return (ViewTypeParser<View>) parentParser;
            } else {
                return (ViewTypeParser<View>) parser;
            }
        }

        if (parser != null) {
            return (ViewTypeParser<View>) parser;
        } else {
            return (ViewTypeParser<View>) parentParser;
        }
    }


    public static ViewTypeParser<View> getViewTypeParser(@NonNull ProteusView view,
                                                         String attributeName) {
        ProteusView.Manager viewManager = view.getViewManager();
        int id = viewManager.getViewTypeParser().getAttributeId(attributeName);
        if (id != -1) {
            //noinspection unchecked
            return viewManager.getViewTypeParser();
        }
        if (view.getAsView().getParent() instanceof ProteusView) {
            ProteusView parent = (ProteusView) view.getAsView().getParent();
            //noinspection unchecked
            return parent.getViewManager().getViewTypeParser();
        }
        return null;
    }

    public static int getAttributeId(@NonNull ProteusView view, String attributeName) {
        ProteusView.Manager viewManager = view.getViewManager();
        int id = viewManager.getViewTypeParser().getAttributeId(attributeName);
        if (id != -1) {
            return id;
        }
        if (view.getAsView().getParent() instanceof ProteusView) {
            ProteusView parent = (ProteusView) view.getAsView().getParent();
            ViewTypeParser<?> parser = parent.getViewManager().getViewTypeParser();
            return parser.getAttributeId(attributeName);
        }
        return -1;
    }

    private static String getAttributeName(ViewTypeParser.AttributeSet attrs, int id) {
        for (Map.Entry<String, ViewTypeParser.AttributeSet.Attribute> entry :
                attrs.getLayoutParamsAttributes().entrySet()) {
            String k = entry.getKey();
            ViewTypeParser.AttributeSet.Attribute v = entry.getValue();
            if (v.id == id) {
                return k;
            }
        }

        for (Map.Entry<String, ViewTypeParser.AttributeSet.Attribute> entry :
                attrs.getAttributes().entrySet()) {
            String k = entry.getKey();
            ViewTypeParser.AttributeSet.Attribute v = entry.getValue();
            if (v.id == id) {
                return k;
            }
        }
        return "Unknown";
    }

    private static Layout.Attribute getChildrenAttribute(ProteusView view) {
        List<Layout.Attribute> attributes = view.getViewManager().getLayout().attributes;
        if (attributes == null) {
            attributes = new ArrayList<>();
        }
        ViewTypeParser.AttributeSet.Attribute attribute =
                view.getViewManager().getAvailableAttributes().get("children");
        if (attribute == null) {
            return null;
        }

        for (Layout.Attribute attr : attributes) {
            if (attribute.id == attr.id) {
                return attr;
            }
        }

        Layout.Attribute layoutAttribute = new Layout.Attribute(attribute.id, new Array());
        attributes.add(layoutAttribute);

        view.getViewManager().getLayout().attributes = attributes;
        return layoutAttribute;
    }
}
