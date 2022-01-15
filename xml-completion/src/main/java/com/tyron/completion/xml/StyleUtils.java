package com.tyron.completion.xml;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.google.common.collect.ImmutableSet;
import com.tyron.completion.xml.model.DeclareStyleable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class StyleUtils {

    private static final Map<String, ImmutableSet<String>> sViewStyleMap = new HashMap<>();

    static {
        putStyle(View.class, "View", "ViewGroup_Layout", "ViewGroup_MarginLayout");
        putStyle(ViewGroup.class, "ViewGroup", "View");
        putStyle(LinearLayout.class, "LinearLayout", "ViewGroup");
        putStyle(FrameLayout.class, "FrameLayout", "ViewGroup");
        putStyle(RelativeLayout.class, "RelativeLayout", "RelativeLayout_Layout", "ViewGroup");
        putStyle(TextView.class, "TextView", "View");
        putStyle(Button.class, "Button", "CompoundButton", "TextView");
    }

    private static void putStyle(Class<? extends View> view, String... styles) {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        for (String style : styles) {
            builder.add(style);
        }
        sViewStyleMap.put(view.getSimpleName(), builder.build());
    }

    public static Set<DeclareStyleable> getStyles(Map<String, DeclareStyleable> map, String name) {
        Set<DeclareStyleable> styles = new TreeSet<>();
        String simpleName = getSimpleName(name);
        if (!map.containsKey(simpleName)) {
            return styles;
        }
        styles.add(map.get(simpleName));

        ImmutableSet<String> strings = sViewStyleMap.get(simpleName);
        if (strings != null) {
            for (String string : strings) {
                if (name.equals(string)) {
                    // already parsed
                    continue;
                }
                styles.addAll(getStyles(map, string));
            }
        }
        return styles;
    }

    private static String getSimpleName(String name) {
        if (name.contains(".")) {
            return name.substring(name.lastIndexOf('.') + 1);
        }
        return name;
    }
}
