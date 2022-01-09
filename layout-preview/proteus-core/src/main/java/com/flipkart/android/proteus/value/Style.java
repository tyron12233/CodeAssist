package com.flipkart.android.proteus.value;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.toolbox.ProteusHelper;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Style extends Value {

    private static final String STYLE_PREFIX = "@style/";

    private final String name;
    /**
     * The name of the parent of this style
     */
    private final String parent;
    private final ObjectValue values = new ObjectValue();

    public Style(@NonNull String name) {
        this.name = name;
        this.parent = null;
    }

    public Style(@NonNull String name, @Nullable String parent) {
        this.name = name;
        this.parent = parent;
    }

    /**
     * Return whether a string value from xml is a style value
     * This checks whether the string starts with {@code @style/}
     */
    public static boolean isStyle(@NonNull String string) {
        return string.startsWith(STYLE_PREFIX);
    }

    public static Value valueOf(String string, ProteusContext context) {
        return context.getProteusResources().getStyle(string);
    }

    /**
     * Get the the value from this style using its name
     *
     * @param name         the name of the attribute
     * @param defaultValue the value returned if the attribute does not exist
     * @return the value corresponding to the name given
     */
    @Nullable
    public Value getValue(@NonNull String name, @Nullable Value defaultValue) {
        Value value = values.get(name);
        if (value != null) {
            return value;
        }
        return defaultValue;
    }

    @Nullable
    public Value getValue(@NonNull String name, ProteusContext context, @Nullable Value def) {
        Style style = this;
        while (style != null) {
            Value value = style.getValue(name, null);
            if (value != null) {
                return value;
            }
            if (style.parent == null) {
                style = null;
            } else {
                style = context.getStyle(style.parent);
            }
        }
        return def;
    }

    public void applyStyle(View parent, ProteusView view, boolean b) {
        ProteusView.Manager viewManager = view.getViewManager();
        ProteusContext context = viewManager.getContext();

        ObjectValue allValues = getAllValues(context);
        for (Map.Entry<String, Value> entry : allValues.entrySet()) {
            int id = ProteusHelper.getAttributeId(view, entry.getKey());
            if (id == -1) {
                id = ProteusHelper.getAttributeId(view, "app:" + entry.getKey());
            }
            if (id != -1) {
                Value value = entry.getValue();
                if (value.isPrimitive()) {
                    value = AttributeProcessor.staticPreCompile(value.getAsPrimitive(), context,
                            context.getFunctionManager());
                }
                if (value == null) {
                    value = entry.getValue();
                }

                viewManager.getViewTypeParser().handleAttribute(parent, (View) view, id, value);
            }
        }

        if (b) {
            if (viewManager.getStyle() == null) {
                viewManager.setStyle(this);
            } else {
//                ObjectValue styleValues = viewManager.getStyle().getValues();
//                for (Map.Entry<String, Value> entry : this.getValues().entrySet()) {
//                    if (!styleValues.has(entry.getKey())) {
//                        styleValues.add(entry.getKey(), entry.getValue());
//                    }
//                }
            }
        }
    }

    public void applyTheme(View parent, ProteusView view, boolean setTheme) {
        ProteusView.Manager viewManager = view.getViewManager();
        ProteusContext context = viewManager.getContext();

        Set<Integer> handledAttributes = new HashSet<>();

        if (setTheme) {
            viewManager.setTheme(this);
        }
        Style style = this;
        while (style != null) {

            ObjectValue values = style.getValues();
            for (Map.Entry<String, Value> entry : values.entrySet()) {

                int id = ProteusHelper.getAttributeId(view, entry.getKey());
                if (id == -1) {
                    id = ProteusHelper.getAttributeId(view, "app:" + entry.getKey());
                }
                if (id != -1) {
                    Value value = entry.getValue();
                    if (value.isPrimitive()) {
                        value = AttributeProcessor.staticPreCompile(value.getAsPrimitive(),
                                context, context.getFunctionManager());
                    }
                    if (value == null) {
                        value = entry.getValue();
                    }

                    if (entry.getKey().equals("materialThemeOverlay")) {
                        System.out.println(value);
                    }

                    if (!handledAttributes.contains(id)) {
                        if (viewManager.getViewTypeParser().handleAttribute(parent,
                                view.getAsView(), id, value)) {
                            handledAttributes.add(id);
                        }
                    }
                }
            }
            if (style.parent != null) {
                style = context.getStyle(style.parent);
            } else {
                style = null;
            }
        }
    }

    /**
     * Apply the attributes of this style to a {@link ProteusView}
     * It will also apply the attributes of the parent theme if it has one
     *
     * @param parent
     * @param view   the view to apply the styles to
     */
    public void applyTheme(View parent, ProteusView view) {
        applyTheme(parent, view, false);
    }

    /**
     * Add an attribute for this style
     *
     * @param name  the name of the attribute
     * @param value the value of the attribute
     */
    public void addValue(String name, String value) {
        values.addProperty(name, value);
    }

    public void addValue(String name, @NonNull Value value) {
        values.addProperty(name, value.toString());
    }

    public ObjectValue getValues() {
        return values;
    }

    public ObjectValue getAllValues(ProteusContext context) {
        ObjectValue values = new ObjectValue();
        Style current = this;
        while (current != null) {

            ObjectValue currentValues = current.getValues();
            for (Map.Entry<String, Value> entry : currentValues.entrySet()) {
                if (!values.has(entry.getKey())) {
                    values.add(entry.getKey(), entry.getValue());
                }
            }
            if (current.parent != null) {
                current = context.getStyle(current.parent);
            } else {
                current = null;
            }
        }

        return values;
    }

    @Override
    public String toString() {
        return "Style{" + "name='" + name + '\'' + ", parent='" + parent + '\'' + ", values=" + values + '}';
    }

    @Override
    public Value copy() {
        return new Style(this.name, this.parent);
    }
}
