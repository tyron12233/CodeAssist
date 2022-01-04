package com.flipkart.android.proteus.value;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.flipkart.android.proteus.toolbox.ProteusHelper;

public class Style extends Value {

    private static final String STYLE_PREFIX = "@style/";

    private final String name;
    /** The name of the parent of this style */
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
     * @param name the name of the attribute
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

    /**
     * Apply the attributes of this style to a {@link ProteusView}
     * It will also apply the attributes of the parent theme if it has one
     * @param view the view to apply the styles to
     */
    public void apply(ProteusView view) {
        ProteusView.Manager viewManager = view.getViewManager();
        ProteusContext context = viewManager.getContext();
        viewManager.setStyle(this);
        Style style = this;
        while (style != null) {
            int id = ProteusHelper.getAttributeId(view, Attributes.View.Style);
            if (id != -1) {
                viewManager.getViewTypeParser().handleAttribute(view.getAsView(), id, style);
            }
            if (style.parent != null) {
                style = context.getStyle(style.parent);
            } else {
                style = null;
            }
        }
    }

    /**
     * Add an attribute for this style
     * @param name the name of the attribute
     * @param value the value of the attribute
     */
    public void addValue(String name, String value) {
        values.addProperty(name, value);
    }

    public ObjectValue getValues() {
        return values;
    }

    @Override
    public String toString() {
        return "Style{" + "name='" + name + '\'' + ", parent='" + parent + '\'' + ", values=" + values + '}';
    }

    @Override
    public Value copy() {
        return null;
    }
}
