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

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringDef;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.toolbox.ProteusHelper;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * ColorResource
 *
 * @author aditya.sharat
 */

public class Resource extends Value {

    public static final String RESOURCE_PREFIX_ANIMATION = "@anim/";
    public static final String RESOURCE_PREFIX_BOOLEAN = "@bool/";
    public static final String RESOURCE_PREFIX_COLOR = "@color/";
    public static final String RESOURCE_PREFIX_DIMENSION = "@dimen/";
    public static final String RESOURCE_PREFIX_DRAWABLE = "@drawable/";
    public static final String RESOURCE_PREFIX_STRING = "@string/";

    public static final String ANDROID_RESOURCE_PREFIX_ANIMATION = "@android:anim/";
    public static final String ANDROID_RESOURCE_PREFIX_BOOLEAN = "@android:bool/";
    public static final String ANDROID_RESOURCE_PREFIX_COLOR = "@android:color/";
    public static final String ANDROID_RESOURCE_PREFIX_DIMENSION = "@android:dimen/";
    public static final String ANDROID_RESOURCE_PREFIX_DRAWABLE = "@android:drawable/";
    public static final String ANDROID_RESOURCE_PREFIX_STRING = "@android:string/";

    public static final String ANIM = "anim";
    public static final String BOOLEAN = "bool";
    public static final String COLOR = "color";
    public static final String DIMEN = "dimen";
    public static final String DRAWABLE = "drawable";
    public static final String STRING = "string";

    public static final Resource NOT_FOUND = new Resource(0);

    public final int resId;
    private final String name;

    public Resource(String name) {
        this.resId = -1;
        this.name = name;
    }
    /**
     * @param resId only provide this for android resources
     * @param name the name of the resource, including the prefix
     */
    public Resource(int resId, String name) {
        this.resId = resId;
        this.name = name;
    }

    private Resource(int id) {
        this(null);
    }

    public static boolean isAnimation(String string) {
        return string.startsWith(RESOURCE_PREFIX_ANIMATION) ||
                string.startsWith(ANDROID_RESOURCE_PREFIX_ANIMATION);
    }

    public static boolean isBoolean(String string) {
        return string.startsWith(RESOURCE_PREFIX_BOOLEAN) ||
                string.startsWith(ANDROID_RESOURCE_PREFIX_BOOLEAN);
    }

    public static boolean isColor(String string) {
        return string.startsWith(RESOURCE_PREFIX_COLOR) ||
                string.startsWith(ANDROID_RESOURCE_PREFIX_COLOR);
    }

    public static boolean isDimension(String string) {
        return string.startsWith(RESOURCE_PREFIX_DIMENSION) ||
                string.startsWith(ANDROID_RESOURCE_PREFIX_DIMENSION);
    }

    public static boolean isDrawable(String string) {
        return string.startsWith(RESOURCE_PREFIX_DRAWABLE) ||
                string.startsWith(ANDROID_RESOURCE_PREFIX_DRAWABLE);
    }

    public static boolean isString(String string) {
        return string.startsWith(RESOURCE_PREFIX_STRING) ||
                string.startsWith(ANDROID_RESOURCE_PREFIX_STRING);
    }

    public static boolean isResource(String string) {
        return isAnimation(string) || isBoolean(string) || isColor(string) || isDimension(string) || isDrawable(string) || isString(string);
    }

    @Nullable
    public static Boolean getBoolean(int resId, Context context) {
        try {
            return context.getResources().getBoolean(resId);
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    @Nullable
    public static Color getColor(String name, ProteusContext context) {
       Value value = context.getProteusResources().getColor(name);
       if (value != null && value.isResource()) {
           value = value.getAsResource().getColor(context);
       }
       if (value != null && value.isColor()) {
           return value.getAsColor();
       }
       return Color.valueOf(name);
    }

    @Nullable
    public static ColorStateList getColorStateList(String name, View parent, View view, ProteusContext context) {
        Value color = context.getProteusResources().getColor(name);
        if (color != null) {
            if (color instanceof Color.StateList || color instanceof Color.LazyStateList) {
                return color.getAsColor().apply(parent, view).colors;
            }
        }
        return null;
    }

    @Nullable
    public static Drawable getDrawable(int resId, Context context) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                return context.getResources().getDrawable(resId, context.getTheme());
            } else {
                //noinspection deprecation
                return context.getResources().getDrawable(resId);
            }
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    @Nullable
    public static Dimension getDimension(String name, ProteusContext context) {
        Value dimension = context.getProteusResources().getDimension(name);
        if (dimension == null) {
            return null;
        }

        if (dimension.toString().endsWith("dp")) {
            return Dimension.valueOf(dimension.toString());
        }

        Value value = AttributeProcessor.staticPreCompile(dimension, context,
                context.getFunctionManager());
        while (value != null && !value.isDimension()) {
            if (value.isResource()) {
                return value.getAsResource().getDimension(context);
            }

            if (value.isAttributeResource()) {
                value = context.obtainStyledAttribute(null, null, value.getAsAttributeResource().getName());
            } else {
                break;
            }
        }
        return null;
    }

    @Nullable
    public static String getString(int resId, Context context) {
        try {
            return context.getString(resId);
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }


    @Nullable
    public static Resource valueOf(String value, @Nullable @ResourceType String type, Context context) {
        if (null == value) {
            return null;
        }
        value = removeAfterSlash(value);
        Resource resource = ResourceCache.cache.get(value);
        if (null == resource) {
            int resId = context.getResources().getIdentifier(value, type, "android");
            resource = resId == 0 ? NOT_FOUND : new Resource(resId, value);
            ResourceCache.cache.put(value, resource);
        }
        return NOT_FOUND == resource ? null : resource;
    }

    public static String removeAfterSlash(@NonNull String value) {
        if (!value.contains("/")) {
            return value;
        }
        int index = value.indexOf('/');
        if (index + 1 > value.length()) {
            return value;
        }
        return value.substring(index + 1);
    }

    public static Resource valueOf(String value, ProteusContext context) {
        if (isAndroid(value)) {
            return valueOf(value, getResourceType(value), context);
        } else {
            return xmlValueOf(value, getResourceType(value), context);
        }
    }

    @ResourceType
    public static String getResourceType(String value) {
        if (isDrawable(value)) {
            return DRAWABLE;
        }
        if (isString(value)) {
            return STRING;
        }
        if (isColor(value)) {
            return COLOR;
        }
        if (isBoolean(value)) {
            return BOOLEAN;
        }
        return null;
    }

    public static Resource xmlValueOf(String value, String type, ProteusContext context) {
        Resource resource = ResourceCache.cache.get(value);
        if (resource != null) {
            return resource;
        }
        resource = new Resource(value);
        ResourceCache.cache.put(value, resource);
        return resource;
    }

    public static boolean isAndroid(@NonNull String value) {
        return value.startsWith("@android");
    }

    @NonNull
    public static Resource valueOf(int resId) {
        return new Resource(resId);
    }

    @Nullable
    public Boolean getBoolean(Context context) {
        return getBoolean(resId, context);
    }

    @Nullable
    public Color getColor(ProteusContext context) {
        return getColor(name, context);
    }

    @Nullable
    public ColorStateList getColorStateList(View view) {
        return getColorStateList(name, null, view, ProteusHelper.getProteusContext(view));
    }


    @Nullable
    public ColorStateList getColorStateList(View parent, View view) {
        return getColorStateList(name, parent, view, ProteusHelper.getProteusContext(view));
    }

    public DrawableValue getProteusDrawable(ProteusContext context) {
        if (name != null) {
            String drawableName = removeAfterSlash(name);
            return context.getProteusResources()
                    .getDrawable(drawableName);
        }
        return null;
    }

    @Nullable
    public Drawable getDrawable(ProteusContext context) {
        return getDrawable(resId, context);
    }

    @Nullable
    public Dimension getDimension(ProteusContext context) {
        return getDimension(name, context);
    }

    @Nullable
    public Integer getInteger(Context context) {
        return getInteger(resId, context);
    }

    @Nullable
    public String getString(ProteusContext context) {
        String value = null;
        if (name != null) {
            if (isAndroid(name)) {
                value = getString(resId, context);
            }
            if (value != null) {
                return value;
            }
            Value string = context.getProteusResources()
                    .getString(name.replace(RESOURCE_PREFIX_STRING, ""));
            value = null != string ? string.getAsString() : null;
        }

        return value == null ? getString(resId, context) : value;
    }

    @Nullable
    public Integer getInteger(int resId, Context context) {
        return context.getResources().getInteger(resId);
    }

    @Override
    public Value copy() {
        return this;
    }

    @StringDef({ANIM, BOOLEAN, COLOR, DRAWABLE, DIMEN, STRING})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResourceType {
    }

    private static class ResourceCache {
        static final LruCache<String, Resource> cache = new LruCache<>(64);
    }

    @Override
    public String toString() {
        if (resId != -1) {
            try {
                return "@" + Resources.getSystem().getResourceName(resId);
            } catch (Resources.NotFoundException ignored) {

            }
        }
        return name;
    }









}
