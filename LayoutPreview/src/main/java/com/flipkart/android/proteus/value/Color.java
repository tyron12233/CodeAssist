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
import android.text.TextUtils;
import android.util.LruCache;
import android.util.StateSet;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * ColorValue
 *
 * @author aditya.sharat
 */
public abstract class Color extends Value {

  private static final String COLOR_PREFIX_LITERAL = "#";

  private static HashMap<String, Integer> sAttributesMap = null;

  @NonNull
  public static Color valueOf(@Nullable String value) {
    return valueOf(value, Int.BLACK);
  }

  @NonNull
  public static Color valueOf(@Nullable String value, @NonNull Color defaultValue) {
    if (null == value || value.length() == 0) {
      return defaultValue;
    }
    Color color = ColorCache.cache.get(value);
    if (null == color) {
      if (isColor(value)) {
        @ColorInt int colorInt = android.graphics.Color.parseColor(value);
        color = new Int(colorInt);
      } else {
        color = defaultValue;
      }
      ColorCache.cache.put(value, color);
    }
    return color;
  }

  public static Color valueOf(ObjectValue value, Context context) {
    if (value.isPrimitive("type")) {
      String colorType = value.getAsString("type");
      if (TextUtils.equals(colorType, "selector")) {

        if (value.isArray("children")) {

          Array children = value.get("children").getAsArray();
          Iterator<Value> iterator = children.iterator();
          ObjectValue child;

          int listAllocated = 20;
          int listSize = 0;
          int[] colorList = new int[listAllocated];
          int[][] stateSpecList = new int[listAllocated][];

          while (iterator.hasNext()) {
            child = iterator.next().getAsObject();
            if (child.size() == 0) {
              continue;
            }
            int j = 0;
            Integer baseColor = null;
            float alphaMod = 1.0f;
            int[] stateSpec = new int[child.size() - 1];
            boolean ignoreItem = false;

            for (Map.Entry<String, Value> entry : child.entrySet()) {
              if (ignoreItem) {
                break;
              }
              if (!entry.getValue().isPrimitive()) {
                continue;
              }

              Integer attributeId = getAttribute(entry.getKey());
              if (null != attributeId) {
                switch (attributeId) {
                  case android.R.attr.type:
                    if (!TextUtils.equals("item", entry.getValue().getAsString())) {
                      ignoreItem = true;
                    }
                    break;
                  case android.R.attr.color:
                    String colorRes = entry.getValue().getAsString();
                    if (!TextUtils.isEmpty(colorRes)) {
                      baseColor = apply(colorRes);
                    }
                    break;
                  case android.R.attr.alpha:
                    String alphaStr = entry.getValue().getAsString();
                    if (!TextUtils.isEmpty(alphaStr)) {
                      alphaMod = Float.parseFloat(alphaStr);
                    }
                    break;
                  default:
                    stateSpec[j++] = entry.getValue().getAsBoolean() ? attributeId : -attributeId;
                    break;
                }
              }
            }
            if (!ignoreItem) {
              stateSpec = StateSet.trimStateSet(stateSpec, j);
              if (null == baseColor) {
                throw new IllegalStateException("No ColorValue Specified");
              }

              if (listSize + 1 >= listAllocated) {
                listAllocated = idealIntArraySize(listSize + 1);
                int[] ncolor = new int[listAllocated];
                System.arraycopy(colorList, 0, ncolor, 0, listSize);
                int[][] nstate = new int[listAllocated][];
                System.arraycopy(stateSpecList, 0, nstate, 0, listSize);
                colorList = ncolor;
                stateSpecList = nstate;
              }

              final int color = modulateColorAlpha(baseColor, alphaMod);


              colorList[listSize] = color;
              stateSpecList[listSize] = stateSpec;
              listSize++;
            }
          }
          if (listSize > 0) {
            int[] colors = new int[listSize];
            int[][] stateSpecs = new int[listSize][];
            System.arraycopy(colorList, 0, colors, 0, listSize);
            System.arraycopy(stateSpecList, 0, stateSpecs, 0, listSize);
            return new StateList(stateSpecs, colors);
          }
        }
      }
    }

    return Int.BLACK;
  }

  private static int apply(String value) {
    Color color = valueOf(value);
    int colorInt;
    if (color instanceof Int) {
      colorInt = ((Int) color).value;
    } else {
      colorInt = Int.BLACK.value;
    }
    return colorInt;
  }

  public static boolean isColor(String color) {
    return color.startsWith(COLOR_PREFIX_LITERAL);
  }

  private static HashMap<String, Integer> getAttributesMap() {
    if (null == sAttributesMap) {
      synchronized (Color.class) {
        if (null == sAttributesMap) {
          sAttributesMap = new HashMap<>(15);
          sAttributesMap.put("type", android.R.attr.type);
          sAttributesMap.put("color", android.R.attr.color);
          sAttributesMap.put("alpha", android.R.attr.alpha);
          sAttributesMap.put("state_pressed", android.R.attr.state_pressed);
          sAttributesMap.put("state_focused", android.R.attr.state_focused);
          sAttributesMap.put("state_selected", android.R.attr.state_selected);
          sAttributesMap.put("state_checkable", android.R.attr.state_checkable);
          sAttributesMap.put("state_checked", android.R.attr.state_checked);
          sAttributesMap.put("state_enabled", android.R.attr.state_enabled);
          sAttributesMap.put("state_window_focused", android.R.attr.state_window_focused);
        }
      }
    }
    return sAttributesMap;
  }

  private static Integer getAttribute(String attribute) {
    return getAttributesMap().get(attribute);
  }

  private static int idealByteArraySize(int need) {
    for (int i = 4; i < 32; i++) {
      if (need <= (1 << i) - 12) {
        return (1 << i) - 12;
      }
    }

    return need;
  }

  private static int idealIntArraySize(int need) {
    return idealByteArraySize(need * 4) / 4;
  }

  private static int constrain(int amount, int low, int high) {
    return amount < low ? low : (amount > high ? high : amount);
  }

  private static int modulateColorAlpha(int baseColor, float alphaMod) {
    if (alphaMod == 1.0f) {
      return baseColor;
    }
    final int baseAlpha = android.graphics.Color.alpha(baseColor);
    final int alpha = constrain((int) (baseAlpha * alphaMod + 0.5f), 0, 255);
    return (baseColor & 0xFFFFFF) | (alpha << 24);
  }

  public abstract Result apply(Context context);

  private static class ColorCache {
    static final LruCache<String, Color> cache = new LruCache<>(64);
  }

  public static class Int extends Color {

    public static final Int BLACK = new Int(0);

    @ColorInt
    public final int value;

    Int(@ColorInt int value) {
      this.value = value;
    }

    public static Int valueOf(int number) {
      if (number == 0) {
        return BLACK;
      }
      String value = String.valueOf(number);
      Color color = ColorCache.cache.get(value);
      if (null == color) {
        color = new Int(number);
        ColorCache.cache.put(value, color);
      }
      return (Int) color;
    }

    @Override
    public Value copy() {
      return this;
    }

    @Override
    public Result apply(Context context) {
      return Result.color(value);
    }
  }

  public static class StateList extends Color {

    public final int[][] states;
    public final int[] colors;

    StateList(int[][] states, int[] colors) {
      this.states = states;
      this.colors = colors;
    }

    public static StateList valueOf(int[][] states, int[] colors) {
      return new StateList(states, colors);
    }

    @Override
    public Value copy() {
      return this;
    }

    @Override
    public Result apply(Context context) {
      ColorStateList colors = new ColorStateList(states, this.colors);
      return Result.colors(colors);
    }
  }

  public static class Result {

    public final int color;

    @Nullable
    public final ColorStateList colors;

    private Result(int color, @Nullable ColorStateList colors) {
      this.color = color;
      this.colors = colors;
    }

    public static Result color(int color) {
      return create(color, null);
    }

    public static Result colors(@NonNull ColorStateList colors) {
      return create(colors.getDefaultColor(), colors);
    }

    public static Result create(int color, @Nullable ColorStateList colors) {
      return new Result(color, colors);
    }
  }

}
