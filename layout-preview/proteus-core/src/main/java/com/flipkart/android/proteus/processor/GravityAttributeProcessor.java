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

package com.flipkart.android.proteus.processor;

import android.content.res.TypedArray;
import android.view.View;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.parser.ParseHelper;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;

/**
 * GravityAttributeProcessor
 *
 * @author aditya.sharat
 */

public abstract class GravityAttributeProcessor<V extends View> extends AttributeProcessor<V> {

  public static final com.flipkart.android.proteus.value.Gravity NO_GRAVITY =
          new com.flipkart.android.proteus.value.Gravity(android.view.Gravity.NO_GRAVITY);

  @Override
  public void handleValue(View parent, V view, Value value) {
    int gravity = android.view.Gravity.NO_GRAVITY;
    if (value instanceof com.flipkart.android.proteus.value.Gravity) {
      gravity = value.getAsInt();
    } else {
      if (value.isPrimitive() && value.getAsPrimitive().isNumber()) {
        gravity = value.getAsInt();
      } else if (value.isPrimitive()) {
        gravity = ParseHelper.parseGravity(value.getAsString());
      }
    }
    //noinspection WrongConstant
    setGravity(view, gravity);
  }

  @Override
  public void handleResource(View parent, V view, Resource resource) {
    Integer gravity = resource.getInteger(view.getContext());
    //noinspection WrongConstant
    setGravity(view, null != gravity ? gravity : android.view.Gravity.NO_GRAVITY);
  }

  @Override
  public void handleAttributeResource(View parent, V view, AttributeResource attribute) {
    TypedArray a = attribute.apply(view.getContext());
    set(view, a);
  }

  @Override
  public void handleStyle(View parent, V view, Style style) {
//    TypedArray a = style.apply(view.getContext());
//    set(view, a);
  }

  private void set(V view, TypedArray a) {
    setGravity(view, a.getInt(0, android.view.Gravity.NO_GRAVITY));
  }

  public abstract void setGravity(V view, @Gravity int gravity);

  @Override
  public Value compile(@Nullable Value value, ProteusContext context) {
    if (null == value) {
      return NO_GRAVITY;
    }
    return ParseHelper.getGravity(value.getAsString());
  }

  @IntDef({android.view.Gravity.NO_GRAVITY,
    android.view.Gravity.TOP,
    android.view.Gravity.BOTTOM,
    android.view.Gravity.LEFT,
    android.view.Gravity.RIGHT,
    android.view.Gravity.START,
    android.view.Gravity.END,
    android.view.Gravity.CENTER_VERTICAL,
    android.view.Gravity.FILL_VERTICAL,
    android.view.Gravity.CENTER_HORIZONTAL,
    android.view.Gravity.FILL_HORIZONTAL,
    android.view.Gravity.CENTER,
    android.view.Gravity.FILL})
  @Retention(RetentionPolicy.SOURCE)
  @Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE, TYPE})
  public @interface Gravity {
  }

}
