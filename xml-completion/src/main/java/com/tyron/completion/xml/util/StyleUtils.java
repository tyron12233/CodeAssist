package com.tyron.completion.xml.util;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsoluteLayout;
import android.widget.Button;
import android.widget.CalendarView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.ViewFlipper;
import android.widget.ViewSwitcher;

import androidx.annotation.NonNull;

import com.google.common.collect.ImmutableSet;
import com.tyron.completion.xml.BytecodeScanner;
import com.tyron.completion.xml.model.DeclareStyleable;

import org.apache.bcel.classfile.JavaClass;

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
        putLayoutParams(GridLayout.class);
        putLayoutParams(TableLayout.class);

        putStyle(View.class);
        putStyle(ViewGroup.class);;
        putStyle(LinearLayout.class);
        putStyle(FrameLayout.class);
        putStyle(ListView.class);
        putStyle(RelativeLayout.class);
        putStyle(EditText.class);
        putStyle(TextView.class);
        putStyle(Button.class);
        putStyle(CompoundButton.class);
        putStyle(ImageButton.class);
        putStyle(CheckBox.class);
        putStyle(ProgressBar.class);
        putStyle(SeekBar.class);
        putStyle(TableLayout.class);
        putStyle(GridLayout.class);
        putStyle(CalendarView.class);
        putStyle(DatePicker.class);
        putStyle(ViewFlipper.class);
        putStyle(ViewSwitcher.class);
    }

    public static void putStyles(JavaClass javaClass) {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        try {
            JavaClass[] superClasses = javaClass.getSuperClasses();
            for (JavaClass superClass : superClasses) {
                if (Object.class.getName().equals(superClass.getClassName())) {
                    continue;
                }
                builder.add(getSimpleName(superClass.getClassName()));
            }
        } catch (ClassNotFoundException e) {
            // ignored
        }
        sViewStyleMap.put(getSimpleName(javaClass.getClassName()), builder.build());

        if (BytecodeScanner.isViewGroup(javaClass)) {
            putLayoutParams(javaClass);
        }
    }

    public static void putLayoutParams(JavaClass javaClass) {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        try {
            JavaClass[] superClasses = javaClass.getSuperClasses();
            for (JavaClass superClass : superClasses) {
                if (Object.class.getName().equals(superClass.getClassName())) {
                    continue;
                }

                if (View.class.getName().equals(superClass.getClassName())) {
                    continue;
                }

                builder.add(getSimpleName(superClass.getClassName()) + "_Layout");
            }
            sLayoutParamsMap.put(getSimpleName(javaClass.getClassName()) + "_Layout", builder.build());
        } catch (ClassNotFoundException e) {
            // ignored
        }
    }

    public static void putLayoutParams(@NonNull Class<? extends ViewGroup> viewGroup) {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        Class<?> current = viewGroup;
        while (current != null) {
            if (Object.class.getName().equals(current.getName())) {
                break;
            }
            // no layout params for view
            if (View.class.getName().equals(current.getName())) {
                break;
            }

            builder.add(current.getSimpleName() + "_Layout");

            if ("ViewGroup".equals(current.getSimpleName())) {
                builder.add("ViewGroup_MarginLayout");
            }

            current = current.getSuperclass();
        }
        sLayoutParamsMap.put(viewGroup.getSimpleName() + "_Layout", builder.build());
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
        if (map.containsKey(simpleName)) {
            styles.add(map.get(simpleName));
        }

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

                params.addAll(getLayoutParam(map, string));
            }
        }
        return params;
    }

    public static String getSimpleName(String name) {
        if (name == null) {
            return "";
        }
        if (name.contains(".")) {
            return name.substring(name.lastIndexOf('.') + 1);
        }
        return name;
    }
}
