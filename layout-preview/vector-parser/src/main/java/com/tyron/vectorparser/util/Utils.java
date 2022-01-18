package com.tyron.vectorparser.util;

import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.processor.ColorResourceProcessor;
import com.flipkart.android.proteus.processor.DimensionAttributeProcessor;
import com.flipkart.android.proteus.value.Dimension;
import com.flipkart.android.proteus.value.Primitive;
import com.flipkart.android.proteus.value.Value;

public class Utils {

    public static int getColor(Value value, ProteusContext context) {
        if (value == null) {
            return Color.WHITE;
        }
        if (value.isPrimitive()) {
            Value staticValue = ColorResourceProcessor.staticCompile(value, context);
            return getColor(staticValue, context);
        }

        if (value.isResource()) {
            com.flipkart.android.proteus.value.Color color =
                    value.getAsResource().getColor(context);
            if (color != null) {
                return color.apply(context).color;
            }
        }

        if (value.isAttributeResource()) {
            Value v = value.getAsAttributeResource().resolve(null, null, context);
            return getColor(v, context);
        }

        if (value.isColor()) {
            return value.getAsColor().apply(context).color;
        }

        return com.flipkart.android.proteus.value.Color.Int.getDefaultColor().value;

    }
    public static int getColorFromString(String value, ProteusContext context) {
        Value staticValue = ColorResourceProcessor.staticCompile(new Primitive(value), context);
        return getColor(staticValue, context);
    }

    public static Path.FillType getFillTypeFromString(String value) {
        Path.FillType fillType = Path.FillType.WINDING;
        if (value.equals("1")) {
            fillType = Path.FillType.EVEN_ODD;
        }
        return fillType;
    }

    public static Paint.Cap getLineCapFromString(String value) {
        switch (value) {
            case "1":
                return Paint.Cap.ROUND;
            case "2":
                return Paint.Cap.SQUARE;
            default:
                return Paint.Cap.BUTT;
        }
    }

    public static Paint.Join getLineJoinFromString(String value) {
        switch (value) {
            case "0":
                return Paint.Join.MITER;
            case "1":
                return Paint.Join.ROUND;
            case "2":
                return Paint.Join.BEVEL;
            default:
                return Paint.Join.MITER;
        }
    }

    public static int getAlphaFromFloat(float value) {
        int newValue = (int) (255 * value);
        return Math.min(255, newValue);
    }

    public static float getAlphaFromInt(int value) {
        return (((float) value) / 255.0f);
    }

    public static int dpToPx(int dp) {
        return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
    }

    public static int pxToDp(int px) {
        return (int) (px / Resources.getSystem().getDisplayMetrics().density);
    }

    public static float getDimension(Value value, ProteusContext context) {
        if (value.isDimension()) {
            return value.getAsDimension().apply(context);
        }

        if (value.isPrimitive()) {
            Value value1 = DimensionAttributeProcessor.staticPreCompile(value, context,
                    context.getFunctionManager());
            if (value1 == null) {
                value1 = Dimension.valueOf(value.toString());
            }
            return getDimension(value1, context);
        }
        if (value.isResource()) {
            Dimension dimension = value.getAsResource().getDimension(context);
            if (dimension != null) {
                return dimension.apply(context);
            }
        }
        if (value.isAttributeResource()) {
            Value resolve = value.getAsAttributeResource().resolve(null, null, context);
            if (resolve != null) {
                return getDimension(resolve, context);
            }
        }
        return 0;
    }
    public static float getFloatFromDimensionString(String value, ProteusContext context) {
        return getDimension(new Primitive(value), context);
    }

    public static boolean isEqual(Object a, Object b) {
        return a == null && b == null || !(a == null || b == null) && a.equals(b);
    }

}