package com.tyron.completion.xml;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsoluteLayout;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.common.collect.ImmutableSet;
import com.tyron.completion.xml.model.DeclareStyleable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class StyleUtils {

    private static final Map<String, ImmutableSet<String>> sViewStyleMap = new HashMap<>();
    private static final Map<String, ImmutableSet<String>> sLayoutParamsMap = new HashMap<>();

    static {
        putLayoutParams(ViewGroup.class);
        putLayoutParams(AbsoluteLayout.class);
        putLayoutParams(FrameLayout.class);
        putLayoutParams(RelativeLayout.class);
        putLayoutParams(LinearLayout.class);

        putStyle(View.class);
        putStyle(ViewGroup.class);;
        putStyle(LinearLayout.class);
        putStyle(FrameLayout.class);
        putStyle(RelativeLayout.class);
        putStyle(TextView.class);
        putStyle(Button.class);
        putStyle(CompoundButton.class);
    }

    private static void putLayoutParams(@NonNull Class<? extends ViewGroup> viewGroup) {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        Class<?> current = viewGroup;
        while (current != null) {
            if ("java.lang.Object".equals(current.getName())) {
                break;
            }
            // no layout params for view
            if ("android.view.View".equals(current.getName())) {
                break;
            }

            builder.add(current.getSimpleName() + "_Layout");
            current = current.getSuperclass();
        }
        sLayoutParamsMap.put(viewGroup.getSimpleName() + "_Layout", builder.build());
    }

    private static void putStyle(Class<? extends View> view, String... styles) {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        for (String style : styles) {
            builder.add(style);
        }
        sViewStyleMap.put(view.getSimpleName(), builder.build());
    }

    private static void putStyle(@NonNull Class<? extends View> view) {
        Class<?> current = view;
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        while (current != null) {
            if ("java.lang.Object".equals(current.getName())) {
                break;
            }
            builder.add(current.getSimpleName());
            current = current.getSuperclass();
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

    public static Set<DeclareStyleable> getLayoutParam(Map<String, DeclareStyleable> map, String name) {
        Set<DeclareStyleable> params = new HashSet<>();
        String simpleName = getSimpleName(name);
        if (!simpleName.endsWith("_Layout")) {
            simpleName += "_Layout";;
        }
        if (!map.containsKey(simpleName)) {
            return params;
        }
        params.add(map.get(simpleName));

        ImmutableSet<String> strings = sLayoutParamsMap.get(simpleName);
        if (strings != null) {
            for (String string : strings) {
                if (simpleName.equals(string)) {
                    continue;
                }

                params.addAll(getLayoutParam(map, name));
            }
        }
        return params;
    }

    private static String getSimpleName(String name) {
        if (name.contains(".")) {
            return name.substring(name.lastIndexOf('.') + 1);
        }
        return name;
    }
}
