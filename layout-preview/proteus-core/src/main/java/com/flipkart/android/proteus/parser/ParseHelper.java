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

package com.flipkart.android.proteus.parser;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.flipkart.android.proteus.ProteusConstants;
import com.flipkart.android.proteus.value.Primitive;
import com.flipkart.android.proteus.value.Value;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import androidx.annotation.Nullable;

/**
 * @author kiran.kumar
 */
public class ParseHelper {

  private static final String TAG = "ParseHelper";

  private static final String FALSE = "false";

  private static final String VISIBLE = "visible";
  private static final String INVISIBLE = "invisible";
  private static final String GONE = "gone";

  private static final String CENTER = "center";
  private static final String CENTER_HORIZONTAL = "center_horizontal";
  private static final String CENTER_VERTICAL = "center_vertical";
  private static final String LEFT = "left";
  private static final String RIGHT = "right";
  private static final String TOP = "top";
  private static final String BOTTOM = "bottom";
  private static final String START = "start";
  private static final String END = "end";
  private static final String MIDDLE = "middle";
  private static final String BEGINNING = "beginning";
  private static final String MARQUEE = "marquee";

  private static final String BOLD = "bold";
  private static final String ITALIC = "italic";
  private static final String BOLD_ITALIC = "bold|italic";

  private static final String TEXT_ALIGNMENT_INHERIT = "inherit";
  private static final String TEXT_ALIGNMENT_GRAVITY = "gravity";
  private static final String TEXT_ALIGNMENT_CENTER = "center";
  private static final String TEXT_ALIGNMENT_TEXT_START = "start";
  private static final String TEXT_ALIGNMENT_TEXT_END = "end";
  private static final String TEXT_ALIGNMENT_VIEW_START = "viewStart";
  private static final String TEXT_ALIGNMENT_VIEW_END = "viewEnd";

  private static final String TWEEN_LOCAL_RESOURCE_STR = "@anim/";
  private static final String ID_STRING_START_PATTERN = "@+id/";
  private static final String ID_STRING_START_PATTERN1 = "@id/";
  private static final String ID_STRING_NORMALIZED_PATTERN = ":id/";

  private static final Map<Integer, Primitive> sVisibilityMap = new HashMap<>();
  private static final Map<String, Primitive> sGravityMap = new HashMap<>();
  private static final Map<String, Integer> sDividerMode = new HashMap<>();
  private static final Map<String, Enum> sEllipsizeMode = new HashMap<>();
  private static final Map<String, Integer> sVisibilityMode = new HashMap<>();
  private static final Map<String, Integer> sTextAlignment = new HashMap<>();
  private static final Map<String, ImageView.ScaleType> sImageScaleType = new HashMap<>();

  static {

    sVisibilityMap.put(View.VISIBLE, new Primitive(View.VISIBLE));
    sVisibilityMap.put(View.INVISIBLE, new Primitive(View.INVISIBLE));
    sVisibilityMap.put(View.GONE, new Primitive(View.GONE));

    sGravityMap.put(LEFT, new Primitive(Gravity.LEFT));
    sGravityMap.put(RIGHT, new Primitive(Gravity.RIGHT));
    sGravityMap.put(TOP, new Primitive(Gravity.TOP));
    sGravityMap.put(BOTTOM, new Primitive(Gravity.BOTTOM));
    sGravityMap.put(START, new Primitive(Gravity.START));
    sGravityMap.put(END, new Primitive(Gravity.END));
    sGravityMap.put(CENTER, new Primitive(Gravity.CENTER));
    sGravityMap.put(CENTER_HORIZONTAL, new Primitive(Gravity.CENTER_HORIZONTAL));
    sGravityMap.put(CENTER_VERTICAL, new Primitive(Gravity.CENTER_VERTICAL));

    sDividerMode.put(END, LinearLayout.SHOW_DIVIDER_END);
    sDividerMode.put(MIDDLE, LinearLayout.SHOW_DIVIDER_MIDDLE);
    sDividerMode.put(BEGINNING, LinearLayout.SHOW_DIVIDER_BEGINNING);

    sEllipsizeMode.put(END, TextUtils.TruncateAt.END);
    sEllipsizeMode.put(START, TextUtils.TruncateAt.START);
    sEllipsizeMode.put(MARQUEE, TextUtils.TruncateAt.MARQUEE);
    sEllipsizeMode.put(MIDDLE, TextUtils.TruncateAt.MIDDLE);

    sVisibilityMode.put(VISIBLE, View.VISIBLE);
    sVisibilityMode.put(INVISIBLE, View.INVISIBLE);
    sVisibilityMode.put(GONE, View.GONE);

    sImageScaleType.put(CENTER, ImageView.ScaleType.CENTER);
    sImageScaleType.put("center_crop", ImageView.ScaleType.CENTER_CROP);
    sImageScaleType.put("center_inside", ImageView.ScaleType.CENTER_INSIDE);
    sImageScaleType.put("fitCenter", ImageView.ScaleType.FIT_CENTER);
    sImageScaleType.put("fit_xy", ImageView.ScaleType.FIT_XY);
    sImageScaleType.put("matrix", ImageView.ScaleType.MATRIX);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
      sTextAlignment.put(TEXT_ALIGNMENT_INHERIT, View.TEXT_ALIGNMENT_INHERIT);
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      sTextAlignment.put(TEXT_ALIGNMENT_GRAVITY, View.TEXT_ALIGNMENT_GRAVITY);
      sTextAlignment.put(TEXT_ALIGNMENT_CENTER, View.TEXT_ALIGNMENT_CENTER);
      sTextAlignment.put(TEXT_ALIGNMENT_TEXT_START, View.TEXT_ALIGNMENT_TEXT_START);
      sTextAlignment.put(TEXT_ALIGNMENT_TEXT_END, View.TEXT_ALIGNMENT_TEXT_END);
      sTextAlignment.put(TEXT_ALIGNMENT_VIEW_START, View.TEXT_ALIGNMENT_VIEW_START);
      sTextAlignment.put(TEXT_ALIGNMENT_VIEW_END, View.TEXT_ALIGNMENT_VIEW_END);
    }
  }

  public static int parseInt(String attributeValue) {
    int number;
    if (ProteusConstants.DATA_NULL.equals(attributeValue)) {
      return 0;
    }
    try {
      number = Integer.parseInt(attributeValue);
    } catch (NumberFormatException e) {
      if (ProteusConstants.isLoggingEnabled()) {
        Log.e(TAG, attributeValue + " is NAN. Error: " + e.getMessage());
      }
      number = 0;
    }
    return number;
  }

  public static IntResult parseIntUnsafe(String s) {
    if (s == null) {
      return new IntResult("null string");
    }

    int num;
    final int len = s.length();
    final char ch = s.charAt(0);
    int d = ch - '0';
    if (d < 0 || d > 9) {
      return new IntResult("Malformed:  " + s);
    }
    num = d;

    int i = 1;
    while (i < len) {
      d = s.charAt(i++) - '0';
      if (d < 0 || d > 9) {
        return new IntResult("Malformed:  " + s);
      }
      num *= 10;
      num += d;
    }

    return new IntResult(null, num);
  }

  public static float parseFloat(String value) {
    float number = 0;
    if (null != value && value.length() > 0) {
      try {
        number = Float.parseFloat(value);
      } catch (NumberFormatException e) {
        number = 0;
      }
    }
    return number;
  }

  public static float parseFloat(Value v) {
    String value;
    if (v.isPrimitive()) {
      value = v.toString();
    } else {
      return 0;
    }
    return parseFloat(value);
  }

  public static double parseDouble(String attributeValue) {
    double number;
    if (ProteusConstants.DATA_NULL.equals(attributeValue)) {
      return 0;
    }
    try {
      number = Double.parseDouble(attributeValue);
    } catch (NumberFormatException e) {
      if (ProteusConstants.isLoggingEnabled()) {
        Log.e(TAG, attributeValue + " is NAN. Error: " + e.getMessage());
      }
      number = 0;
    }
    return number;
  }

  public static String parseViewId(String id) {
    if (id.startsWith(ID_STRING_START_PATTERN)) {
      return id.substring(ID_STRING_START_PATTERN.length());
    }
    if (id.startsWith(ID_STRING_START_PATTERN1)) {
      return id.substring(ID_STRING_START_PATTERN1.length());
    }
    return id;
  }

  public static int parseGravity(String value) {
    String[] gravities = value.split("\\|");
    int returnGravity = Gravity.NO_GRAVITY;
    for (String gravity : gravities) {
      Primitive gravityValue = sGravityMap.get(gravity);
      if (null != gravityValue) {
        returnGravity |= gravityValue.getAsInt();
      }
    }
    return returnGravity;
  }

  public static com.flipkart.android.proteus.value.Gravity getGravity(String value) {
    return new com.flipkart.android.proteus.value.Gravity(parseGravity(value));
  }

  public static int parseDividerMode(String attributeValue) {
    Integer returnValue = sDividerMode.get(attributeValue);
    return returnValue == null ? LinearLayout.SHOW_DIVIDER_NONE : returnValue;
  }

  public static Enum parseEllipsize(String attributeValue) {
    Enum returnValue = sEllipsizeMode.get(attributeValue);
    return returnValue == null ? TextUtils.TruncateAt.END : returnValue;
  }

  public static int parseVisibility(@Nullable Value value) {
    Integer returnValue = null;
    if (null != value && value.isPrimitive()) {
      String attributeValue = value.getAsString();
      returnValue = sVisibilityMode.get(attributeValue);
      if (null == returnValue &&
        (attributeValue.isEmpty() || FALSE.equals(attributeValue) || ProteusConstants.DATA_NULL.equals(attributeValue))) {
        returnValue = View.GONE;
      }
    } else //noinspection ConstantConditions
      if (value.isNull()) {
        returnValue = View.GONE;
      }
    return returnValue == null ? View.VISIBLE : returnValue;
  }

  public static Primitive getVisibility(int visibility) {
    Primitive value = sVisibilityMap.get(visibility);
    return null != value ? value : sVisibilityMap.get(View.GONE);
  }

  public static int parseColor(String color) {
    try {
      return Color.parseColor(color);
    } catch (IllegalArgumentException ex) {
      if (ProteusConstants.isLoggingEnabled()) {
        Log.e(TAG, "Invalid color : " + color + ". Using #000000");
      }
      return Color.BLACK;
    }
  }

  public static boolean parseBoolean(@Nullable Value value) {
    // TODO: we should consider 0 as false too.
    return null != value && value.isPrimitive() && value.getAsPrimitive().isBoolean() ? value.getAsBoolean() : null != value && !value.isNull() && Boolean.parseBoolean(value.getAsString());
  }

  public static int parseRelativeLayoutBoolean(boolean value) {
    return value ? RelativeLayout.TRUE : 0;
  }

  public static void addRelativeLayoutRule(View view, int verb, int anchor) {
    ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
    if (layoutParams instanceof RelativeLayout.LayoutParams) {
      RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) layoutParams;
      params.addRule(verb, anchor);
      view.setLayoutParams(params);
    } else {
      if (ProteusConstants.isLoggingEnabled()) {
        Log.e(TAG, "cannot add relative layout rules when container is not relative");
      }
    }
  }

  public static int parseTextStyle(String attributeValue) {
    int typeface = Typeface.NORMAL;
    if (attributeValue != null) {
      attributeValue = attributeValue.toLowerCase();
      switch (attributeValue) {
        case BOLD:
          typeface = Typeface.BOLD;
          break;
        case ITALIC:
          typeface = Typeface.ITALIC;
          break;
        case BOLD_ITALIC:
          typeface = Typeface.BOLD_ITALIC;
          break;
        default:
          typeface = Typeface.NORMAL;
          break;
      }
    }
    return typeface;
  }

  public static boolean isTweenAnimationResource(String attributeValue) {
    return attributeValue.startsWith(TWEEN_LOCAL_RESOURCE_STR);
  }

  /**
   * Uses reflection to fetch the R.id from the given class.
   * This method is faster than using {@link android.content.res.Resources#getResourceName(int)}
   *
   * @param variableName the name of the variable
   * @param с            The class
   * @return resource id
   */
  public static int getResId(String variableName, Class<?> с) {
    Field field;
    int resId = 0;
    try {
      field = с.getField(variableName);
      resId = field.getInt(null);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return resId;

  }

  /**
   * Get int resource id, by just passing the string value of android:id from xml file.
   * Note : This method only works for @android:id or @+android:id right now
   *
   * @param fullResIdString the string id of the view
   * @return the number id of the view
   */
  public static int getAndroidXmlResId(String fullResIdString) {
    if (fullResIdString != null) {
      int i = fullResIdString.indexOf("/");
      if (i >= 0) {
        String idString = fullResIdString.substring(i + 1);
        return getResId(idString, android.R.id.class);
      }
    }
    return View.NO_ID;
  }

  /**
   * Parses a image view scale type
   *
   * @param attributeValue value of the scale type attribute
   * @return {@link android.widget.ImageView.ScaleType} enum
   */
  public static ImageView.ScaleType parseScaleType(String attributeValue) {
    return !TextUtils.isEmpty(attributeValue) ? sImageScaleType.get(attributeValue) : null;
  }

  /**
   * parses Text Alignment
   *
   * @param attributeValue value of the typeface attribute
   * @return the text alignment value
   */
  public static Integer parseTextAlignment(String attributeValue) {
    return !TextUtils.isEmpty(attributeValue) ? sTextAlignment.get(attributeValue) : null;
  }

  public static class IntResult {
    @Nullable
    public final String error;
    public final int result;

    public IntResult(@Nullable String error, int result) {
      this.error = error;
      this.result = result;
    }

    public IntResult(@Nullable String error) {
      this.error = error;
      this.result = -1;
    }
  }
}
