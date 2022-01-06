/*
 * Copyright 2019 Flipkart Internet Pvt. Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flipkart.android.proteus.value;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.LevelListDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusLayoutInflater;
import com.flipkart.android.proteus.exceptions.ProteusInflateException;
import com.flipkart.android.proteus.parser.ParseHelper;
import com.flipkart.android.proteus.processor.ColorResourceProcessor;
import com.flipkart.android.proteus.processor.DimensionAttributeProcessor;
import com.flipkart.android.proteus.processor.DrawableResourceProcessor;
import com.flipkart.android.proteus.toolbox.SimpleArrayIterator;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * DrawableValue
 *
 * @author aditya.sharat
 */
public abstract class DrawableValue extends Value {

    private static final String TYPE = "type";
    private static final String CHILDREN = "children";

    private static final String DRAWABLE_SELECTOR = "selector";
    private static final String DRAWABLE_SHAPE = "shape";
    private static final String DRAWABLE_LAYER_LIST = "layer-list";
    private static final String DRAWABLE_LEVEL_LIST = "level-list";
    private static final String DRAWABLE_RIPPLE = "ripple";
    private static final String DRAWABLE_INSET = "inset";

    private static final String TYPE_CORNERS = "corners";
    private static final String TYPE_GRADIENT = "gradient";
    private static final String TYPE_PADDING = "padding";
    private static final String TYPE_SIZE = "size";
    private static final String TYPE_SOLID = "solid";
    private static final String TYPE_STROKE = "stroke";

    @Nullable
    public static DrawableValue valueOf(String value, ProteusContext context) {
        if (Color.isColor(value)) {
            return ColorValue.valueOf(value);
        } else {
            return UrlValue.valueOf(value);
        }
    }

    public static DrawableValue valueOf(@NonNull File file) {
        if (!file.exists()) {
            return null;
        }

        return new FileValue(file);
    }

    @Nullable
    public static DrawableValue valueOf(ObjectValue value, ProteusContext context) {
        String type = value.getAsString(TYPE);
        //noinspection ConstantConditions
        switch (type) {
            case DRAWABLE_SELECTOR:
                return StateListValue.valueOf(value.getAsArray(CHILDREN), context);
            case DRAWABLE_SHAPE:
                return ShapeValue.valueOf(value, context);
            case DRAWABLE_LAYER_LIST:
                return LayerListValue.valueOf(value.getAsArray(CHILDREN), context);
            case DRAWABLE_LEVEL_LIST:
                return LevelListValue.valueOf(value.getAsArray(CHILDREN), context);
            case DRAWABLE_RIPPLE:
                return RippleValue.valueOf(value, context);
            case DRAWABLE_INSET:
                return new InsetValue(value, context);
            default:
                return null;
        }
    }

    @NonNull
    public static Drawable convertBitmapToDrawable(Bitmap original, ProteusContext context) {

        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        int width = original.getWidth();
        int height = original.getHeight();

        float scaleWidth = displayMetrics.scaledDensity;
        float scaleHeight = displayMetrics.scaledDensity;
        Matrix matrix = new Matrix();
        matrix.postScale(scaleWidth, scaleHeight);

        Bitmap resizedBitmap = Bitmap.createBitmap(original, 0, 0, width, height, matrix, true);

        return new BitmapDrawable(context.getResources(), resizedBitmap);
    }

    public abstract void apply(View view, ProteusContext context, ProteusLayoutInflater.ImageLoader loader, Callback callback);

    @Override
    public Value copy() {
        return this;
    }

    public interface Callback {
        void apply(Drawable drawable);
    }

    private static class FileValue extends DrawableValue {

        private final File mFile;

        private FileValue(File file) {
            mFile = file;
        }

        @Override
        public void apply(View view, ProteusContext context, ProteusLayoutInflater.ImageLoader loader, Callback callback) {
            Glide.with(view)
                    .asDrawable()
                    .load(mFile)
                    .into(new CustomTarget<Drawable>() {
                        @Override
                        public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                            callback.apply(resource);
                        }

                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {

                        }
                    });
        }
    }

    public static class ColorValue extends DrawableValue {

        public static final ColorValue BLACK = new ColorValue(Color.Int.getDefaultColor());

        public final Value color;

        private ColorValue(Value color) {
            this.color = color;
        }

        public static ColorValue valueOf(String value) {
            return new ColorValue(Color.valueOf(value));
        }

        public static ColorValue valueOf(Value value, ProteusContext context) {
            return new ColorValue(ColorResourceProcessor.staticCompile(value, context));
        }

        @Override
        public void apply(View view, ProteusContext context, ProteusLayoutInflater.ImageLoader loader, Callback callback) {
            Drawable drawable = new ColorDrawable(ColorResourceProcessor.evaluate(color, view).color);
            callback.apply(drawable);
        }

        @NonNull
        @Override
        public String toString() {
            return color.toString();
        }
    }

    public static class InsetValue extends DrawableValue {

        private static final String INSET_LEFT = "android:insetLeft";
        private static final String INSET_TOP = "android:insetTop";
        private static final String INSET_RIGHT = "android:insetRight";
        private static final String INSET_BOTTOM = "android:insetBottom";

        private Dimension insetLeft;
        private Dimension insetTop;
        private Dimension insetRight;
        private Dimension insetBottom;

        private final DrawableValue drawableValue;

        private InsetValue(ObjectValue value, ProteusContext context) {
            Primitive insetLeft;
            Primitive insetTop;
            Primitive insetRight;
            Primitive insetBottom;

            insetLeft = value.getAsPrimitive(INSET_LEFT);
            insetTop = value.getAsPrimitive(INSET_TOP);
            insetRight = value.getAsPrimitive(INSET_RIGHT);
            insetBottom = value.getAsPrimitive(INSET_BOTTOM);

            if (insetLeft != null) {
                this.insetLeft = Dimension.valueOf(insetLeft.toString());
            }
            if (insetTop != null) {
                this.insetTop = Dimension.valueOf(insetTop.toString());
            }
            if (insetRight != null) {
                this.insetRight = Dimension.valueOf(insetRight.toString());
            }
            if (insetBottom != null) {
                this.insetBottom = Dimension.valueOf(insetBottom.toString());
            }

            DrawableValue drawableValue = null;
            if (value.has(CHILDREN)) {
                Value children = value.get(CHILDREN);
                if (children.isArray()) {
                    Value first = children.getAsArray().get(0);
                    if (first.isObject()) {
                        drawableValue = DrawableValue.valueOf(first.getAsObject(), context);
                    }
                }
            }
            this.drawableValue = drawableValue;
        }

        @Override
        public void apply(View view, ProteusContext context,
                          ProteusLayoutInflater.ImageLoader loader, Callback callback) {
            if (drawableValue != null) {
                final int insetLeft;
                final int insetTop;
                final int insetRight;
                final int insetBottom;

                if (this.insetLeft != null) {
                    insetLeft = (int) this.insetLeft.apply(context);
                } else {
                    insetLeft = -1;
                }
                if (this.insetTop != null) {
                    insetTop = (int) this.insetTop.apply(context);
                } else {
                    insetTop = -1;
                }
                if (this.insetRight != null) {
                    insetRight = (int) this.insetRight.apply(context);
                } else {
                    insetRight = -1;
                }
                if (this.insetBottom != null) {
                    insetBottom = (int) this.insetBottom.apply(context);
                } else {
                    insetBottom = -1;
                }

                drawableValue.apply(view, context, loader, drawable -> {
                    InsetDrawable insetDrawable = new InsetDrawable(drawable, insetLeft, insetTop, insetRight, insetBottom);
                    callback.apply(insetDrawable);
                });
            }
        }
    }


    public static class ShapeValue extends DrawableValue {

        private static final int SHAPE_NONE = -1;

        private static final String SHAPE_RECTANGLE = "rectangle";
        private static final String SHAPE_OVAL = "oval";
        private static final String SHAPE_LINE = "line";
        private static final String SHAPE_RING = "ring";

        private static final String SHAPE = "shape";

        public final int shape;

        @Nullable
        public final Gradient gradient;

        @Nullable
        private final DrawableElement[] elements;

        private ShapeValue(ObjectValue value, ProteusContext context) {
            this.shape = getShape(value.getAsString(SHAPE));

            Gradient gradient = null;
            DrawableElement[] elements = null;
            if (value.has(CHILDREN)) {
                Value childrenValue = value.get(CHILDREN);
                if (childrenValue.isArray()) {
                    Array children = childrenValue.getAsArray();
                    if (children != null) {
                        Iterator<Value> iterator = children.iterator();
                        if (children.size() > 0) {
                            elements = new DrawableElement[children.size()];
                            int index = 0;
                            while (iterator.hasNext()) {
                                ObjectValue child = iterator.next().getAsObject();
                                String typeKey = child.getAsString(TYPE);
                                DrawableElement element = null;
                                //noinspection ConstantConditions
                                switch (typeKey) {
                                    case TYPE_CORNERS:
                                        element = Corners.valueOf(child, context);
                                        break;
                                    case TYPE_PADDING:
                                        break;
                                    case TYPE_SIZE:
                                        element = Size.valueOf(child, context);
                                        break;
                                    case TYPE_SOLID:
                                        element = Solid.valueOf(child, context);
                                        break;
                                    case TYPE_STROKE:
                                        element = Stroke.valueOf(child, context);
                                        break;
                                    case TYPE_GRADIENT:
                                        gradient = Gradient.valueOf(child, context);
                                        element = gradient;
                                        break;
                                }

                                if (null != element) {
                                    elements[index] = element;
                                    index++;
                                }
                            }
                        }
                    }
                }
            }

            this.elements = elements;
            this.gradient = gradient;
        }

        private ShapeValue(int shape, @Nullable Gradient gradient, @Nullable DrawableElement[] elements) {
            this.shape = shape;
            this.gradient = gradient;
            this.elements = elements;
        }

        public static ShapeValue valueOf(ObjectValue value, ProteusContext context) {
            return new ShapeValue(value, context);
        }

        public static ShapeValue valueOf(int shape, @Nullable Gradient gradient, @Nullable DrawableElement[] elements) {
            return new ShapeValue(shape, gradient, elements);
        }

        private static int getShape(String shape) {
            if (shape == null) {
                return SHAPE_NONE;
            }
            switch (shape) {
                case SHAPE_RECTANGLE:
                    return GradientDrawable.RECTANGLE;
                case SHAPE_OVAL:
                    return GradientDrawable.OVAL;
                case SHAPE_LINE:
                    return GradientDrawable.LINE;
                case SHAPE_RING:
                    return GradientDrawable.RING;
                default:
                    return SHAPE_NONE;

            }
        }

        @Override
        public void apply(View view, ProteusContext context, ProteusLayoutInflater.ImageLoader loader, Callback callback) {
            GradientDrawable drawable = null != gradient ? gradient.init(view) : new GradientDrawable();
            if (-1 != shape) {
                drawable.setShape(shape);
            }
            if (null != elements) {
                for (DrawableElement element : elements) {
                    element.apply(view, drawable);
                }
            }
            callback.apply(drawable);
        }
    }

    public static class LayerListValue extends DrawableValue {

        private static final String ID_STR = "id";
        private static final String DRAWABLE_STR = "drawable";

        private final int[] ids;
        private final Value[] layers;

        private LayerListValue(Array layers, ProteusContext context) {
            this.ids = new int[layers.size()];
            this.layers = new Value[layers.size()];
            Pair<Integer, Value> pair;
            int index = 0;
            Iterator<Value> iterator = layers.iterator();
            while (iterator.hasNext()) {
                pair = parseLayer(iterator.next().getAsObject(), context);
                this.ids[index] = pair.first;
                this.layers[index] = pair.second;
                index++;
            }
        }

        public LayerListValue(int[] ids, Value[] layers) {
            this.ids = ids;
            this.layers = layers;
        }

        public static LayerListValue valueOf(Array layers, ProteusContext context) {
            return new LayerListValue(layers, context);
        }

        public static LayerListValue valueOf(@NonNull int[] ids, @NonNull Value[] layers) {
            return new LayerListValue(ids, layers);
        }

        public static Pair<Integer, Value> parseLayer(ObjectValue layer, ProteusContext context) {
            String id = layer.getAsString(ID_STR);
            int resId = View.NO_ID;
            if (id != null) {
                resId = ParseHelper.getAndroidXmlResId(id);
            }
            Value drawable = layer.get(DRAWABLE_STR);
            Value out = DrawableResourceProcessor.staticCompile(drawable, context);
            return new Pair<>(resId, out);
        }

        @Override
        public void apply(View view, ProteusContext context, ProteusLayoutInflater.ImageLoader loader, Callback callback) {
            final Drawable[] drawables = new Drawable[layers.length];
            int index = 0;
            for (Value layer : layers) {
                drawables[index] = DrawableResourceProcessor.evaluate(layer, view);
                index++;
            }

            LayerDrawable layerDrawable = new LayerDrawable(drawables);

            // we could have avoided the following loop if layer drawable
            // had a method to add drawable and set id at same time
            for (int i = 0; i < drawables.length; i++) {
                layerDrawable.setId(i, ids[i]);
            }

            callback.apply(layerDrawable);
        }

        public Iterator<Integer> getIds() {
            return SimpleArrayIterator.createIntArrayIterator(ids);
        }

        public Iterator<Value> getLayers() {
            return new SimpleArrayIterator<>(layers);
        }
    }

    public static class StateListValue extends DrawableValue {

        private static final String DRAWABLE_STR = "drawable";
        public static final Map<String, Integer> sStateMap = new HashMap<>();

        static {
            sStateMap.put("android:state_pressed", android.R.attr.state_pressed);
            sStateMap.put("android:state_enabled", android.R.attr.state_enabled);
            sStateMap.put("android:state_focused", android.R.attr.state_focused);
            sStateMap.put("android:state_hovered", android.R.attr.state_hovered);
            sStateMap.put("android:state_selected", android.R.attr.state_selected);
            sStateMap.put("android:state_checkable", android.R.attr.state_checkable);
            sStateMap.put("android:state_checked", android.R.attr.state_checked);
            sStateMap.put("android:state_activated", android.R.attr.state_activated);
            sStateMap.put("android:state_window_focused", android.R.attr.state_window_focused);
        }

        public final int[][] states;
        private final Value[] values;

        private StateListValue(int[][] states, Value[] values) {
            this.states = states;
            this.values = values;
        }

        private StateListValue(Array states, ProteusContext context) {
            this.states = new int[states.size()][];
            this.values = new Value[states.size()];
            Iterator<Value> iterator = states.iterator();
            Pair<int[], Value> pair;
            int index = 0;
            while (iterator.hasNext()) {
                pair = parseState(iterator.next().getAsObject(), context);
                this.states[index] = pair.first;
                this.values[index] = pair.second;
                index++;
            }
        }

        public static StateListValue valueOf(int[][] states, Value[] values) {
            return new StateListValue(states, values);
        }

        public static StateListValue valueOf(Array states, ProteusContext context) {
            return new StateListValue(states, context);
        }

        @NonNull
        private static Pair<int[], Value> parseState(ObjectValue value, ProteusContext context) {
            Value drawable = DrawableResourceProcessor.staticCompile(value.get(DRAWABLE_STR), context);
            int[] states = new int[value.getAsObject().entrySet().size() - 1];
            int index = 0;
            for (Map.Entry<String, Value> entry : value.getAsObject().entrySet()) {
                Integer stateInteger = sStateMap.get(entry.getKey());
                if (stateInteger != null) {
                    // e.g state_pressed = true state_pressed = false
                    states[index] = ParseHelper.parseBoolean(entry.getValue()) ? stateInteger : -stateInteger;
                    index++;
                } else {
                    throw new IllegalArgumentException(entry.getKey() + " is not a valid state");
                }
            }
            return new Pair<>(states, drawable);
        }

        @Override
        public void apply(View view, ProteusContext context, ProteusLayoutInflater.ImageLoader loader, Callback callback) {
            final StateListDrawable stateListDrawable = new StateListDrawable();
            int size = states.length;
            for (int i = 0; i < size; i++) {
                stateListDrawable.addState(states[i], DrawableResourceProcessor.evaluate(values[i], view));
            }
            callback.apply(stateListDrawable);
        }

        public Iterator<Value> getValues() {
            return new SimpleArrayIterator<>(values);
        }
    }

    public static class LevelListValue extends DrawableValue {

        private final Level[] levels;

        private LevelListValue(Array levels, ProteusContext context) {
            this.levels = new Level[levels.size()];
            int index = 0;
            Iterator<Value> iterator = levels.iterator();
            while (iterator.hasNext()) {
                this.levels[index] = new Level(iterator.next().getAsObject(), context);
                index++;
            }
        }

        private LevelListValue(Level[] levels) {
            this.levels = levels;
        }

        public static LevelListValue valueOf(Array layers, ProteusContext context) {
            return new LevelListValue(layers, context);
        }

        public static LevelListValue value(Level[] levels) {
            return new LevelListValue(levels);
        }

        @Override
        public void apply(View view, ProteusContext context, ProteusLayoutInflater.ImageLoader loader, Callback callback) {
            final LevelListDrawable levelListDrawable = new LevelListDrawable();
            for (Level level : levels) {
                level.apply(view, levelListDrawable);
            }
        }

        public Iterator<Level> getLevels() {
            return new SimpleArrayIterator<>(levels);
        }

        public static class Level {
            public static final String MIN_LEVEL = "minLevel";
            public static final String MAX_LEVEL = "maxLevel";
            public static final String DRAWABLE = "drawable";

            public final int minLevel;
            public final int maxLevel;

            @NonNull
            public final Value drawable;

            Level(ObjectValue value, ProteusContext context) {

                //noinspection ConstantConditions
                minLevel = value.getAsInteger(MIN_LEVEL);

                //noinspection ConstantConditions
                maxLevel = value.getAsInteger(MAX_LEVEL);

                drawable = DrawableResourceProcessor.staticCompile(value.get(DRAWABLE), context);
            }

            private Level(int minLevel, int maxLevel, @NonNull Value drawable) {
                this.minLevel = minLevel;
                this.maxLevel = maxLevel;
                this.drawable = drawable;
            }

            @NonNull
            public static Level valueOf(int minLevel, int maxLevel, @NonNull Value drawable, ProteusContext context) {
                return new Level(minLevel, maxLevel, DrawableResourceProcessor.staticCompile(drawable, context));
            }

            public void apply(View view, final LevelListDrawable levelListDrawable) {
                levelListDrawable.addLevel(minLevel, maxLevel, DrawableResourceProcessor.evaluate(drawable, view));
            }
        }
    }

    public static class RippleValue extends DrawableValue {

        public static final String COLOR = "android:color";
        public static final String MASK = "mask";
        public static final String CONTENT = "android:content";
        public static final String DEFAULT_BACKGROUND = "android:defaultBackground";
        public static final String MASK_ID = "@android:id/mask";
        @NonNull
        public final Value color;

        @Nullable
        public final Value mask;

        @Nullable
        public final Value content;

        @Nullable
        public final Value defaultBackground;

        public RippleValue(@NonNull Value color, @Nullable Value mask, @Nullable Value content, @Nullable Value defaultBackground) {
            this.color = color;
            this.mask = mask;
            this.content = content;
            this.defaultBackground = defaultBackground;
        }

        private RippleValue(ObjectValue object, ProteusContext context) {
            color = object.get(COLOR);
            mask = getMask(object, context);
            content = DrawableResourceProcessor.staticCompile(object.get(CONTENT), context);
            defaultBackground = DrawableResourceProcessor.staticCompile(object.get(DEFAULT_BACKGROUND), context);
        }

        private Value getMask(ObjectValue object, ProteusContext context) {
            Array children = object.getAsArray("children");
            if (children == null) {
                return null;
            }
            for (int i = 0; i < children.size(); i++) {
                Value child = children.get(i);
                if (child.isObject()) {
                    ObjectValue childObject = child.getAsObject();
                    String type = childObject.getAsString("type");
                    String id = childObject.getAsString("android:id");
                    if ("item".equals(type) && MASK_ID.equals(id)) {
                        return getMaskItem(childObject, context);
                    }
                }
            }
            return null;
        }

        private Value getMaskItem(ObjectValue object, ProteusContext context) {
            Array children = object.getAsArray("children");
            if (children == null) {
                return null;
            }
            for (int i = 0; i < children.size(); i++) {
                Value child = children.get(i);
                if (child.isObject()) {
                    return DrawableResourceProcessor.staticCompile(child, context);
                }
            }
            return null;
        }

        public static RippleValue valueOf(@NonNull Value color, @Nullable Value mask, @Nullable Value content, @Nullable Value defaultBackground) {
            return new RippleValue(color, mask, content, defaultBackground);
        }

        public static RippleValue valueOf(ObjectValue value, ProteusContext context) {
            return new RippleValue(value, context);
        }

        @Override
        public void apply(View view, ProteusContext context, ProteusLayoutInflater.ImageLoader loader, Callback callback) {
            ColorStateList colorStateList;
            Drawable contentDrawable = null;
            Drawable maskDrawable = null;

            Drawable resultDrawable = null;

            Color.Result result = ColorResourceProcessor.evaluate(color, view);

            if (null != result.colors) {
                colorStateList = result.colors;
            } else {
                int[][] states = new int[][]{
                        new int[]{}
                };

                int[] colors = new int[]{
                        result.color
                };
                colorStateList = new ColorStateList(states, colors);
            }

            if (null != content) {
                contentDrawable = DrawableResourceProcessor.evaluate(content, view);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (null != mask) {
                    maskDrawable = DrawableResourceProcessor.evaluate(mask, view);
                }
                resultDrawable = new RippleDrawable(colorStateList, contentDrawable, maskDrawable);
            } else if (null != defaultBackground) {
                resultDrawable = DrawableResourceProcessor.evaluate(defaultBackground, view);
            } else if (contentDrawable != null) {
                int pressedColor = colorStateList.getColorForState(new int[]{android.R.attr.state_pressed}, colorStateList.getDefaultColor());
                int focusedColor = colorStateList.getColorForState(new int[]{android.R.attr.state_focused}, pressedColor);
                ColorDrawable pressedColorDrawable = new ColorDrawable(pressedColor);
                ColorDrawable focusedColorDrawable = new ColorDrawable(focusedColor);
                StateListDrawable stateListDrawable = new StateListDrawable();
                stateListDrawable.addState(new int[]{android.R.attr.state_enabled, android.R.attr.state_pressed}, pressedColorDrawable);
                stateListDrawable.addState(new int[]{android.R.attr.state_enabled, android.R.attr.state_focused}, focusedColorDrawable);
                stateListDrawable.addState(new int[]{android.R.attr.state_enabled}, contentDrawable);
                resultDrawable = stateListDrawable;
            }

            callback.apply(resultDrawable);
        }
    }

    public static class UrlValue extends DrawableValue {

        public final String url;

        private UrlValue(String url) {
            this.url = url;
        }

        public static UrlValue valueOf(String url) {
            return new UrlValue(url);
        }

        @Override
        public void apply(final View view, ProteusContext context, ProteusLayoutInflater.ImageLoader loader, final Callback callback) {
            loader.getBitmap(view, url, new AsyncCallback() {
                @Override
                protected void apply(@NonNull Drawable drawable) {
                    callback.apply(drawable);
                }

                @Override
                protected void apply(@NonNull Bitmap bitmap) {
                    callback.apply(convertBitmapToDrawable(bitmap, context));
                }
            });
        }

        @NonNull
        @Override
        public String toString() {
            return url;
        }
    }

    /**
     *
     */
    private static abstract class DrawableElement extends Value {

        public abstract void apply(View view, GradientDrawable drawable);

        @Override
        public Value copy() {
            return this;
        }
    }

    public static class Gradient extends DrawableElement {

        public static final String ANGLE = "android:angle";
        public static final String CENTER_X = "android:centerX";
        public static final String CENTER_Y = "android:centerY";
        public static final String CENTER_COLOR = "android:centerColor";
        public static final String END_COLOR = "android:endColor";
        public static final String GRADIENT_RADIUS = "android:gradientRadius";
        public static final String START_COLOR = "android:startColor";
        public static final String GRADIENT_TYPE = "android:gradientType";
        public static final String USE_LEVEL = "android:useLevel";

        private static final int GRADIENT_TYPE_NONE = -1;

        private static final String LINEAR_GRADIENT = "linear";
        private static final String RADIAL_GRADIENT = "radial";
        private static final String SWEEP_GRADIENT = "sweep";

        @Nullable
        public final Integer angle;

        @Nullable
        public final Float centerX;

        @Nullable
        public final Float centerY;

        @Nullable
        public final Value centerColor;

        @Nullable
        public final Value endColor;

        @Nullable
        public final Value gradientRadius;

        @Nullable
        public final Value startColor;

        public final int gradientType;

        @Nullable
        public final Boolean useLevel;

        private Gradient(ObjectValue gradient, ProteusContext context) {
            angle = gradient.getAsInteger(ANGLE);
            centerX = gradient.getAsFloat(CENTER_X);
            centerY = gradient.getAsFloat(CENTER_Y);

            Value value;

            value = gradient.get(START_COLOR);
            if (value != null) {
                startColor = ColorResourceProcessor.staticCompile(value, context);
            } else {
                startColor = null;
            }

            value = gradient.get(CENTER_COLOR);
            if (value != null) {
                centerColor = ColorResourceProcessor.staticCompile(value, context);
            } else {
                centerColor = null;
            }

            value = gradient.get(END_COLOR);
            if (value != null) {
                endColor = ColorResourceProcessor.staticCompile(value, context);
            } else {
                endColor = null;
            }

            value = gradient.get(GRADIENT_RADIUS);
            if (value != null) {
                gradientRadius = DimensionAttributeProcessor.staticCompile(value, context);
            } else {
                gradientRadius = null;
            }

            gradientType = getGradientType(gradient.getAsString(GRADIENT_TYPE));

            useLevel = gradient.getAsBoolean(USE_LEVEL);
        }

        public static Gradient valueOf(ObjectValue value, ProteusContext context) {
            return new Gradient(value, context);
        }

        public static GradientDrawable.Orientation getOrientation(@Nullable Integer angle) {
            GradientDrawable.Orientation orientation = GradientDrawable.Orientation.LEFT_RIGHT;
            if (null != angle) {
                angle %= 360;
                if (angle % 45 == 0) {
                    switch (angle) {
                        case 0:
                            orientation = GradientDrawable.Orientation.LEFT_RIGHT;
                            break;
                        case 45:
                            orientation = GradientDrawable.Orientation.BL_TR;
                            break;
                        case 90:
                            orientation = GradientDrawable.Orientation.BOTTOM_TOP;
                            break;
                        case 135:
                            orientation = GradientDrawable.Orientation.BR_TL;
                            break;
                        case 180:
                            orientation = GradientDrawable.Orientation.RIGHT_LEFT;
                            break;
                        case 225:
                            orientation = GradientDrawable.Orientation.TR_BL;
                            break;
                        case 270:
                            orientation = GradientDrawable.Orientation.TOP_BOTTOM;
                            break;
                        case 315:
                            orientation = GradientDrawable.Orientation.TL_BR;
                            break;
                    }
                }
            }
            return orientation;
        }

        public static GradientDrawable init(@Nullable int[] colors, @Nullable Integer angle) {
            return colors != null ? new GradientDrawable(getOrientation(angle), colors) : new GradientDrawable();
        }

        @Override
        public void apply(View view, final GradientDrawable drawable) {
            if (null != centerX && null != centerY) {
                drawable.setGradientCenter(centerX, centerY);
            }

            if (null != gradientRadius) {
                drawable.setGradientRadius(DimensionAttributeProcessor.evaluate(gradientRadius, view));
            }

            if (GRADIENT_TYPE_NONE != gradientType) {
                drawable.setGradientType(gradientType);
            }
        }

        private int getGradientType(@Nullable String type) {
            if (null == type) {
                return GRADIENT_TYPE_NONE;
            }
            switch (type) {
                case LINEAR_GRADIENT:
                    return GradientDrawable.LINEAR_GRADIENT;
                case RADIAL_GRADIENT:
                    return GradientDrawable.RADIAL_GRADIENT;
                case SWEEP_GRADIENT:
                    return GradientDrawable.SWEEP_GRADIENT;
                default:
                    return GRADIENT_TYPE_NONE;
            }
        }

        public GradientDrawable init(View view) {
            int[] colors;
            if (centerColor != null) {
                colors = new int[3];
                colors[0] = ColorResourceProcessor.evaluate(startColor, view).color;
                colors[1] = ColorResourceProcessor.evaluate(centerColor, view).color;
                colors[2] = ColorResourceProcessor.evaluate(endColor, view).color;
            } else {
                colors = new int[2];
                colors[0] = ColorResourceProcessor.evaluate(startColor, view).color;
                colors[1] = ColorResourceProcessor.evaluate(endColor, view).color;
            }

            return init(colors, angle);
        }
    }

    public static class Corners extends DrawableElement {

        public static final String RADIUS = "android:radius";
        public static final String TOP_LEFT_RADIUS = "android:topLeftRadius";
        public static final String TOP_RIGHT_RADIUS = "android:topRightRadius";
        public static final String BOTTOM_LEFT_RADIUS = "android:bottomLeftRadius";
        public static final String BOTTOM_RIGHT_RADIUS = "android:bottomRightRadius";

        @Nullable
        private final Value radius;
        @Nullable
        private final Value topLeftRadius;
        @Nullable
        private final Value topRightRadius;
        @Nullable
        private final Value bottomLeftRadius;
        @Nullable
        private final Value bottomRightRadius;

        private Corners(ObjectValue corner, ProteusContext context) {
            radius = DimensionAttributeProcessor.staticCompile(corner.get(RADIUS), context);
            topLeftRadius = DimensionAttributeProcessor.staticCompile(corner.get(TOP_LEFT_RADIUS), context);
            topRightRadius = DimensionAttributeProcessor.staticCompile(corner.get(TOP_RIGHT_RADIUS), context);
            bottomLeftRadius = DimensionAttributeProcessor.staticCompile(corner.get(BOTTOM_LEFT_RADIUS), context);
            bottomRightRadius = DimensionAttributeProcessor.staticCompile(corner.get(BOTTOM_RIGHT_RADIUS), context);
        }

        public static Corners valueOf(ObjectValue corner, ProteusContext context) {
            return new Corners(corner, context);
        }

        @Override
        public void apply(View view, GradientDrawable gradientDrawable) {
            if (null != radius) {
                gradientDrawable.setCornerRadius(DimensionAttributeProcessor.evaluate(radius, view));
            }

            float fTopLeftRadius = DimensionAttributeProcessor.evaluate(topLeftRadius, view);
            float fTopRightRadius = DimensionAttributeProcessor.evaluate(topRightRadius, view);
            float fBottomRightRadius = DimensionAttributeProcessor.evaluate(bottomRightRadius, view);
            float fBottomLeftRadius = DimensionAttributeProcessor.evaluate(bottomLeftRadius, view);

            if (fTopLeftRadius != 0 || fTopRightRadius != 0 || fBottomRightRadius != 0 || fBottomLeftRadius != 0) {
                // The corner radii are specified in clockwise order (see Path.addRoundRect())
                gradientDrawable.setCornerRadii(new float[]{
                        fTopLeftRadius, fTopLeftRadius,
                        fTopRightRadius, fTopRightRadius,
                        fBottomRightRadius, fBottomRightRadius,
                        fBottomLeftRadius, fBottomLeftRadius
                });
            }
        }
    }

    public static class Solid extends DrawableElement {

        public static final String COLOR = "android:color";

        private final Value color;

        private Solid(ObjectValue value, ProteusContext context) {
            color = ColorResourceProcessor.staticCompile(value.get(COLOR), context);
        }

        public static Solid valueOf(ObjectValue value, ProteusContext context) {
            return new Solid(value, context);
        }

        @Override
        public void apply(View view, final GradientDrawable gradientDrawable) {
            Color.Result result = ColorResourceProcessor.evaluate(color, view);
            if (null != result.colors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                gradientDrawable.setColor(result.colors);
            } else {
                gradientDrawable.setColor(result.color);
            }
        }
    }

    public static class Size extends DrawableElement {

        private static final String WIDTH = "android:width";
        private static final String HEIGHT = "android:height";

        private final Value width;
        private final Value height;

        private Size(ObjectValue value, ProteusContext context) {
            width = DimensionAttributeProcessor.staticCompile(value.get(WIDTH), context);
            height = DimensionAttributeProcessor.staticCompile(value.get(HEIGHT), context);
        }

        public static Size valueOf(ObjectValue value, ProteusContext context) {
            return new Size(value, context);
        }

        @Override
        public void apply(View view, GradientDrawable gradientDrawable) {
            gradientDrawable.setSize((int) DimensionAttributeProcessor.evaluate(width, view), (int) DimensionAttributeProcessor.evaluate(height, view));
        }
    }

    public static class Stroke extends DrawableElement {

        public static final String WIDTH = "android:width";
        public static final String COLOR = "android:color";
        public static final String DASH_WIDTH = "android:dashWidth";
        public static final String DASH_GAP = "android:dashGap";

        public final Value width;
        public final Value color;
        public final Value dashWidth;
        public final Value dashGap;

        private Stroke(ObjectValue stroke, ProteusContext context) {
            width = DimensionAttributeProcessor.staticCompile(stroke.get(WIDTH), context);
            color = ColorResourceProcessor.staticCompile(stroke.get(COLOR), context);
            dashWidth = DimensionAttributeProcessor.staticCompile(stroke.get(DASH_WIDTH), context);
            dashGap = DimensionAttributeProcessor.staticCompile(stroke.get(DASH_GAP), context);
        }

        public static Stroke valueOf(ObjectValue stroke, ProteusContext context) {
            return new Stroke(stroke, context);
        }

        @Override
        public void apply(View view, GradientDrawable gradientDrawable) {
            if (null == dashWidth) {
                gradientDrawable.setStroke((int) DimensionAttributeProcessor.evaluate(width, view), ColorResourceProcessor.evaluate(color, view).color);
            } else if (null != dashGap) {
                gradientDrawable.setStroke((int) DimensionAttributeProcessor.evaluate(width, view),
                        ColorResourceProcessor.evaluate(color, view).color,
                        DimensionAttributeProcessor.evaluate(dashWidth, view),
                        DimensionAttributeProcessor.evaluate(dashGap, view));
            }
        }
    }

    /**
     * AsyncCallback
     *
     * @author aditya.sharat
     */
    public abstract static class AsyncCallback {

        private boolean recycled;

        protected AsyncCallback() {
        }

        public void setBitmap(@NonNull Bitmap bitmap) {
            if (recycled) {
                throw new ProteusInflateException("Cannot make calls to a recycled instance!");
            }
            apply(bitmap);
            recycled = true;
        }

        public void setDrawable(@NonNull Drawable drawable) {
            if (recycled) {
                throw new ProteusInflateException("Cannot make calls to a recycled instance!");
            }
            apply(drawable);
            recycled = true;
        }

        protected abstract void apply(@NonNull Drawable drawable);

        protected abstract void apply(@NonNull Bitmap bitmap);
    }
}
