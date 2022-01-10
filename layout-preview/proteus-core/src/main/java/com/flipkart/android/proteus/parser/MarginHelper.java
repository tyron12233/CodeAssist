package com.flipkart.android.proteus.parser;

import android.view.View;
import android.view.ViewGroup;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

public class MarginHelper {

    public static class Margins {
        public int left;
        public int top;
        public int right;
        public int bottom;
    }

    private static final Map<String, Margins> sMarginMap = new HashMap<>();

    public static void setMarginLeft(View view, int d) {
        sMarginMap.compute(view.toString(), (s, margins) -> {
            if (margins == null) {
                margins = new Margins();
            }
            margins.left = d;
            return margins;
        });
        setMargins(view);
    }

    public static void setMarginTop(View view, int d) {
        sMarginMap.compute(view.toString(), (s, margins) -> {
            if (margins == null) {
                margins = new Margins();
            }
            margins.top = d;
            return margins;
        });
        setMargins(view);
    }

    public static void setMarginRight(View view, int d) {
        sMarginMap.compute(view.toString(), (s, margins) -> {
            if (margins == null) {
                margins = new Margins();
            }
            margins.right = d;
            return margins;
        });
        setMargins(view);
    }

    public static void setMarginBottom(View view, int d) {
        sMarginMap.compute(view.toString(), (s, margins) -> {
            if (margins == null) {
                margins = new Margins();
            }
            margins.bottom = d;
            return margins;
        });
        setMargins(view);
    }
    

    private static void setMargins(View view) {
        Margins margins = sMarginMap.get(view.toString());
        if (margins == null) {
            return;
        }
        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        layoutParams.leftMargin = margins.left;
        layoutParams.topMargin = margins.top;
        layoutParams.rightMargin = margins.right;
        layoutParams.bottomMargin = margins.bottom;
    }
}
