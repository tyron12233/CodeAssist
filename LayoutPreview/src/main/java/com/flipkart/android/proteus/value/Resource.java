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
import android.os.Build;
import android.util.LruCache;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringDef;

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

  public static final String ANIM = "anim";
  public static final String BOOLEAN = "bool";
  public static final String COLOR = "color";
  public static final String DIMEN = "dimen";
  public static final String DRAWABLE = "drawable";
  public static final String STRING = "string";

  public static final Resource NOT_FOUND = new Resource(0);

  public final int resId;

  private Resource(int resId) {
    this.resId = resId;
  }

  public static boolean isAnimation(String string) {
    return string.startsWith(RESOURCE_PREFIX_ANIMATION);
  }

  public static boolean isBoolean(String string) {
    return string.startsWith(RESOURCE_PREFIX_BOOLEAN);
  }

  public static boolean isColor(String string) {
    return string.startsWith(RESOURCE_PREFIX_COLOR);
  }

  public static boolean isDimension(String string) {
    return string.startsWith(RESOURCE_PREFIX_DIMENSION);
  }

  public static boolean isDrawable(String string) {
    return string.startsWith(RESOURCE_PREFIX_DRAWABLE);
  }

  public static boolean isString(String string) {
    return string.startsWith(RESOURCE_PREFIX_STRING);
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
  public static Integer getColor(int resId, Context context) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        return context.getResources().getColor(resId, context.getTheme());
      } else {
        //noinspection deprecation
        return context.getResources().getColor(resId);
      }
    } catch (Resources.NotFoundException e) {
      return null;
    }
  }

  @Nullable
  public static ColorStateList getColorStateList(int resId, Context context) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        return context.getColorStateList(resId);
      } else {
        //noinspection deprecation
        return context.getResources().getColorStateList(resId);
      }

    } catch (Resources.NotFoundException nfe) {
      return null;
    }
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
  public static Float getDimension(int resId, Context context) {
    try {
      return context.getResources().getDimension(resId);
    } catch (Resources.NotFoundException e) {
      return null;
    }
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
    Resource resource = ResourceCache.cache.get(value);
    if (null == resource) {
      int resId = context.getResources().getIdentifier(value, type, context.getPackageName());
      resource = resId == 0 ? NOT_FOUND : new Resource(resId);
      ResourceCache.cache.put(value, resource);
    }
    return NOT_FOUND == resource ? null : resource;
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
  public Integer getColor(Context context) {
    return getColor(resId, context);
  }

  @Nullable
  public ColorStateList getColorStateList(Context context) {
    return getColorStateList(resId, context);
  }

  @Nullable
  public Drawable getDrawable(Context context) {
    return getDrawable(resId, context);
  }

  @Nullable
  public Float getDimension(Context context) {
    return getDimension(resId, context);
  }

  @Nullable
  public Integer getInteger(Context context) {
    return getInteger(resId, context);
  }

  @Nullable
  public String getString(Context context) {
    return getString(resId, context);
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
}
